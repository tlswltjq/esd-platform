package com.stove.catalog;

import static com.stove.catalog.CatalogFixtures.envelope;
import static com.stove.catalog.CatalogFixtures.event;
import static com.stove.catalog.CatalogFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.catalog.api.listener.ReleaseSubmissionMapper;
import com.stove.catalog.core.domain.ListingChange;
import com.stove.catalog.core.domain.ListingPreference;
import com.stove.catalog.core.service.ChangeCatalogListing;
import com.stove.catalog.core.service.PublishProjectRelease;
import com.stove.catalog.core.service.ReadCatalogListing;
import com.stove.common.kafka.contract.payload.ProjectReleaseRequestedEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

@TestPropertySource(properties = "stove.catalog.store-service-key=listing-store-test")
class CatalogListingIntegrationTest extends CatalogIntegrationTestBase {
    @Autowired PublishProjectRelease publish;
    @Autowired ChangeCatalogListing change;
    @Autowired ReadCatalogListing read;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoBean JwtDecoder decoder;
    @LocalServerPort int port;

    @BeforeEach void tokens() {
        when(decoder.decode(anyString())).thenAnswer(call -> {
            String token = call.getArgument(0, String.class);
            String role = "admin".equals(token) ? "ADMIN" : "member".equals(token) ? "MEMBER" : "CREATOR";
            return Jwt.withTokenValue(token).header("alg", "RS256").subject("owner".equals(token) ? "creator" : token)
                    .claim("roles", List.of(role)).issuedAt(Instant.now().minusSeconds(60)).expiresAt(Instant.now().plusSeconds(300)).build();
        });
    }
    record Response(int status, JsonNode body, String cache) { JsonNode data() { return body.path("data"); } }
    Response call(String method, String path, String token, Object body) {
        RestClient.RequestBodySpec request = RestClient.create("http://127.0.0.1:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json").body(body);
        return request.exchange((r, s) -> new Response(s.getStatusCode().value(), mapper.readTree(s.getBody()), s.getHeaders().getCacheControl()));
    }
    Response internal(String key, boolean jwt) {
        RestClient.RequestHeadersSpec<?> request = RestClient.create("http://127.0.0.1:" + port).get().uri("/internal/v1/store-products?size=100");
        if (key != null) request.header("X-Store-Service-Key", key);
        if (jwt) request.header("Authorization", "Bearer admin");
        return request.exchange((r, s) -> new Response(s.getStatusCode().value(), s.getStatusCode().is2xxSuccessful() ? mapper.readTree(s.getBody()) : mapper.createObjectNode(), s.getHeaders().getCacheControl()));
    }
    String publish(long project, long version) {
        ProjectReleaseRequestedEvent e = event(project, version);
        publish.execute(ReleaseSubmissionMapper.from(envelope(e), e));
        return e.partitionKey();
    }
    String own(String code) { return "/api/v1/products/by-code/" + code + "/listing"; }
    String admin(String code) { return "/api/v1/admin/products/" + code + "/listing/restriction"; }
    Map<String, Object> preference(String value, long version) { return Map.of("preference", value, "expectedCatalogVersion", version); }
    Map<String, Object> hidden(boolean value, long version) { return Map.of("hidden", value, "expectedCatalogVersion", version); }
    boolean listed(String code) { return read.platform(code).listed(); }
    int audits(String code) { return jdbc.queryForObject("SELECT COUNT(*) FROM catalog_listing_change c JOIN catalog_product p ON c.product_id=p.id WHERE p.product_code=?", Integer.class, code); }
    boolean publicListContains(String code) { return call("GET", "/api/v1/products?size=100", null, null).data().path("content").toString().contains(code); }

    @Test void automaticDefaultTracksSalesAndIndependentOverridesAlsoControlPublicDetail() {
        String code = publish(id(), 1);
        assertThat(listed(code)).isTrue();
        assertThat(publicListContains(code)).isTrue();
        call("PUT", "/api/v1/products/by-code/" + code + "/sales", "owner", Map.of("saleStatus", "STOPPED", "expectedCatalogVersion", 1));
        assertThat(listed(code)).isFalse();
        assertThat(publicListContains(code)).isFalse();
        Response override = call("PUT", own(code), "owner", preference("LISTED", 2));
        assertThat(override.status()).isEqualTo(200); assertThat(override.cache()).isEqualTo("no-store");
        assertThat(publicListContains(code)).isTrue();
        call("PUT", own(code), "owner", preference("UNLISTED", 3));
        call("PUT", "/api/v1/products/by-code/" + code + "/sales", "owner", Map.of("saleStatus", "ON_SALE", "expectedCatalogVersion", 4));
        assertThat(publicListContains(code)).isFalse();
        assertThat(call("GET", "/api/v1/products/by-code/" + code, null, null).status()).isEqualTo(404);
        call("PUT", own(code), "owner", preference("AUTO", 5));
        assertThat(publicListContains(code)).isTrue();
        call("PUT", "/api/v1/admin/products/" + code + "/sales/restriction", "admin", Map.of("restricted", true, "expectedCatalogVersion", 6));
        assertThat(publicListContains(code)).isFalse();
        call("PUT", "/api/v1/admin/products/" + code + "/sales/restriction", "admin", Map.of("restricted", false, "expectedCatalogVersion", 7));
        assertThat(publicListContains(code)).isTrue();
        assertThat(audits(code)).isEqualTo(3);
    }
    @Test void administratorHideCannotBeReleasedBySellerOrPublicationAndKeepsTheSellerChoice() {
        long project = id(); String code = publish(project, 1);
        call("PUT", admin(code), "admin", hidden(true, 1));
        call("PUT", own(code), "owner", preference("LISTED", 2));
        assertThat(listed(code)).isFalse();
        assertThat(publicListContains(code)).isFalse();
        publish(project, 2);
        assertThat(read.platform(code).platformHidden()).isTrue();
        assertThat(read.platform(code).listingPreference()).isEqualTo(ListingPreference.LISTED);
        assertThat(listed(code)).isFalse();
        assertThat(call("GET", "/api/v1/products/by-code/" + code, null, null).status()).isEqualTo(404);
        call("PUT", own(code), "owner", preference("UNLISTED", 4));
        call("PUT", admin(code), "admin", hidden(false, 5));
        assertThat(read.platform(code).platformHidden()).isFalse();
        assertThat(listed(code)).isFalse();
        assertThat(audits(code)).isEqualTo(4);
    }
    @Test void rolesOwnershipVersionAndMalformedInputsDoNotWriteUnapprovedChanges() {
        String code = publish(id(), 1);
        assertThat(call("PUT", own(code), null, preference("UNLISTED", 1)).status()).isEqualTo(401);
        assertThat(call("PUT", own(code), "member", preference("UNLISTED", 1)).status()).isEqualTo(403);
        assertThat(call("PUT", own(code), "other", preference("UNLISTED", 1)).status()).isEqualTo(404);
        assertThat(call("PUT", own(code), "admin", preference("UNLISTED", 1)).status()).isEqualTo(403);
        assertThat(call("PUT", admin(code), "owner", hidden(true, 1)).status()).isEqualTo(403);
        assertThat(call("PUT", own(code), "owner", preference("INVALID", 1)).status()).isEqualTo(400);
        assertThat(call("PUT", admin(code), "admin", Map.of("expectedCatalogVersion", 1)).status()).isEqualTo(400);
        assertThat(call("PUT", own(code), "owner", preference("AUTO", 1)).status()).isEqualTo(200);
        assertThat(audits(code)).isZero();
        call("PUT", own(code), "owner", preference("UNLISTED", 1));
        assertThat(call("PUT", own(code), "owner", preference("UNLISTED", 1)).status()).isEqualTo(409);
        assertThat(audits(code)).isEqualTo(1);
    }
    @Test void protectedWholeCatalogSyncIncludesHiddenProductsWithoutOpeningOtherEndpoints() {
        String code = publish(id(), 1);
        call("PUT", own(code), "owner", preference("UNLISTED", 1));
        assertThat(publicListContains(code)).isFalse();
        assertThat(internal(null, false).status()).isEqualTo(401);
        assertThat(internal("wrong", false).status()).isEqualTo(401);
        assertThat(internal(null, true).status()).isEqualTo(401);
        Response page = internal("listing-store-test", false);
        assertThat(page.status()).isEqualTo(200); assertThat(page.cache()).isEqualTo("no-store");
        assertThat(page.data().path("content").toString()).contains(code);
        assertThat(call("GET", "/v3/api-docs", null, null).body().path("paths").has("/internal/v1/store-products")).isFalse();
        int restricted = RestClient.create("http://127.0.0.1:" + port).get().uri("/internal/v1/products/" + code + "/build")
                .header("X-Store-Service-Key", "listing-store-test").exchange((r, s) -> s.getStatusCode().value());
        assertThat(restricted).isEqualTo(401);
    }
    @Test void actualAuditAndOutboxInsertFailuresRollBackModeVersionAndEveryRecord() throws Exception {
        String code = publish(id(), 1);
        long productId = jdbc.queryForObject("SELECT id FROM catalog_product WHERE product_code=?", Long.class, code);
        try {
            jdbc.execute("ALTER TABLE catalog_listing_change ADD CONSTRAINT reject_listing_audit CHECK(product_id<>" + productId + ")");
            assertThatThrownBy(() -> change.seller("creator", new ListingChange(code, ListingPreference.UNLISTED, 1))).isInstanceOf(RuntimeException.class);
        } finally { jdbc.execute("ALTER TABLE catalog_listing_change DROP CHECK reject_listing_audit"); }
        assertThat(read.platform(code).catalogVersion()).isEqualTo(1); assertThat(audits(code)).isZero();
        try {
            jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT reject_listing_outbox CHECK(aggregate_id<>'" + code
                    + "' OR JSON_UNQUOTE(JSON_EXTRACT(payload,'$.product.listed'))<>'false')");
            assertThatThrownBy(() -> change.seller("creator", new ListingChange(code, ListingPreference.UNLISTED, 1))).isInstanceOf(RuntimeException.class);
        } finally { jdbc.execute("ALTER TABLE outbox_event DROP CHECK reject_listing_outbox"); }
        assertThat(read.platform(code).catalogVersion()).isEqualTo(1); assertThat(audits(code)).isZero();
        change.seller("creator", new ListingChange(code, ListingPreference.UNLISTED, 1));
        assertThat(audits(code)).isEqualTo(1);
        String event = jdbc.queryForObject("SELECT payload FROM outbox_event WHERE aggregate_id=? AND event_type='CatalogProductPublished' ORDER BY id DESC LIMIT 1", String.class, code);
        assertThat(mapper.readTree(event).path("product").path("listed").booleanValue()).isFalse();
        assertThat(event).doesNotContain("ownerSubject", "platformListingHidden", "listingPreference");
    }
    @Test void concurrentSellerAndAdministratorCommandsHaveOnlyOneWinningVersion() throws Exception {
        String code = publish(id(), 1);
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> seller = pool.submit(() -> { start.await(); return call("PUT", own(code), "owner", preference("UNLISTED", 1)).status(); });
            Future<Integer> admin = pool.submit(() -> { start.await(); return call("PUT", admin(code), "admin", hidden(true, 1)).status(); });
            start.countDown();
            assertThat(List.of(seller.get(20, TimeUnit.SECONDS), admin.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
            assertThat(audits(code)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
}
