package com.stove.catalog;

import static com.stove.catalog.CatalogFixtures.envelope;
import static com.stove.catalog.CatalogFixtures.event;
import static com.stove.catalog.CatalogFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.catalog.api.listener.ReleaseSubmissionMapper;
import com.stove.catalog.core.domain.ListingChange;
import com.stove.catalog.core.domain.ListingPreference;
import com.stove.catalog.core.domain.PlatformListingChange;
import com.stove.catalog.core.domain.SellerSaleChange;
import com.stove.catalog.core.service.ChangeCatalogListing;
import com.stove.catalog.core.service.ChangeSellerSaleStatus;
import com.stove.catalog.core.service.PublishProjectRelease;
import com.stove.common.kafka.contract.payload.ProjectReleaseRequestedEvent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

@TestPropertySource(properties = {"stove.catalog.store-service-key=detail-store-test", "stove.catalog.order-service-key=detail-order-test"})
class PublicProductDetailIntegrationTest extends CatalogIntegrationTestBase {
    @Autowired PublishProjectRelease publish;
    @Autowired ChangeCatalogListing listing;
    @Autowired ChangeSellerSaleStatus sales;
    @Autowired ObjectMapper mapper;
    @MockitoBean JwtDecoder decoder;
    @LocalServerPort int port;
    @BeforeEach void tokens() {
        when(decoder.decode(anyString())).thenAnswer(call -> Jwt.withTokenValue(call.getArgument(0, String.class))
                .header("alg", "RS256").subject("admin").claim("roles", List.of("ADMIN"))
                .issuedAt(Instant.now().minusSeconds(60)).expiresAt(Instant.now().plusSeconds(300)).build());
    }
    record Response(int status, JsonNode body, String cache) { JsonNode data() { return body.path("data"); } }
    Response get(String path, String header, String credential) {
        RestClient.RequestHeadersSpec<?> request = RestClient.create("http://127.0.0.1:" + port).get().uri(path);
        if (header != null) request.header(header, credential);
        return request.exchange((r, s) -> {
            byte[] bytes = s.getBody().readAllBytes();
            return new Response(s.getStatusCode().value(), bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes), s.getHeaders().getCacheControl());
        });
    }
    String publish(long project, long version) {
        ProjectReleaseRequestedEvent e = event(project, version);
        publish.execute(ReleaseSubmissionMapper.from(envelope(e), e)); return e.partitionKey();
    }
    String publicPath(String code) { return "/api/v1/products/by-code/" + code; }
    String storePath(String code) { return "/internal/v1/store-products/" + code; }
    String orderPath(String code) { return "/internal/v1/order-products/" + code; }
    @Test void hiddenPublicDetailIs404WhileProtectedBusinessReadsKeepCurrentFacts() {
        String code = publish(id(), 1);
        assertThat(get(publicPath(code), null, null).status()).isEqualTo(200);
        listing.seller("creator", new ListingChange(code, ListingPreference.UNLISTED, 1));
        Response denied = get(publicPath(code), null, null);
        assertThat(denied.status()).isEqualTo(404); assertThat(denied.cache()).contains("no-store");
        assertThat(denied.body().path("error").path("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(denied.body().has("data")).isFalse();
        assertThat(get(publicPath(code), "Authorization", "Bearer admin").status()).isEqualTo(404);
        Response store = get(storePath(code), "X-Store-Service-Key", "detail-store-test");
        assertThat(store.status()).isEqualTo(200); assertThat(store.cache()).isEqualTo("no-store");
        assertThat(store.data().path("listed").booleanValue()).isFalse();
        Response order = get(orderPath(code), "X-Order-Service-Key", "detail-order-test");
        assertThat(order.status()).isEqualTo(200); assertThat(order.cache()).isEqualTo("no-store");
        assertThat(order.data().path("publicDetailAllowed").isBoolean()).isTrue();
        assertThat(order.data().path("publicDetailAllowed").booleanValue()).isFalse();
        assertThat(order.data().path("saleStatus").asText()).isEqualTo("ON_SALE");
        assertThat(order.data().path("price").asLong()).isEqualTo(1000);
        assertThat(order.data().has("content")).isFalse();
        assertThat(order.data().toString()).doesNotContain("storagePath", "ownerSubject", "thumbnailUrl", "listingPreference");
        listing.seller("creator", new ListingChange(code, ListingPreference.AUTO, 2));
        assertThat(get(publicPath(code), null, null).status()).isEqualTo(200);
        assertThat(get(orderPath(code), "X-Order-Service-Key", "detail-order-test").data().path("publicDetailAllowed").booleanValue()).isTrue();
    }
    @Test void salesDefaultAndAnExplicitListingUseTheSamePublicDetailPolicy() {
        String code = publish(id(), 1);
        sales.execute("creator", new SellerSaleChange(code, "STOPPED", 1));
        assertThat(get(publicPath(code), null, null).status()).isEqualTo(404);
        assertThat(get(orderPath(code), "X-Order-Service-Key", "detail-order-test").data().path("saleStatus").asText()).isEqualTo("STOPPED");
        listing.seller("creator", new ListingChange(code, ListingPreference.LISTED, 2));
        assertThat(get(publicPath(code), null, null).status()).isEqualTo(200);
        assertThat(get(orderPath(code), "X-Order-Service-Key", "detail-order-test").data().path("publicDetailAllowed").booleanValue()).isTrue();
    }
    @Test void administratorHidingSurvivesPublicationAndRemainsReadableForSynchronization() {
        long project = id(); String code = publish(project, 1);
        listing.platform("admin", new PlatformListingChange(code, true, 1));
        publish(project, 2);
        assertThat(get(publicPath(code), null, null).status()).isEqualTo(404);
        Response store = get(storePath(code), "X-Store-Service-Key", "detail-store-test");
        assertThat(store.status()).isEqualTo(200);
        assertThat(store.data().path("contentVersion").asLong()).isEqualTo(2);
        assertThat(store.data().path("listed").booleanValue()).isFalse();
        listing.platform("admin", new PlatformListingChange(code, false, 3));
        assertThat(get(publicPath(code), null, null).status()).isEqualTo(200);
    }
    @Test void serviceKeysCannotCrossScopesAndInternalPathsAreNotPublicDocumentation() {
        String code = publish(id(), 1);
        for (String path : List.of(storePath(code), orderPath(code))) {
            assertThat(get(path, null, null).status()).isEqualTo(401);
            assertThat(get(path, "Authorization", "Bearer admin").status()).isEqualTo(401);
        }
        assertThat(get(storePath(code), "X-Store-Service-Key", "detail-order-test").status()).isEqualTo(401);
        assertThat(get(orderPath(code), "X-Order-Service-Key", "detail-store-test").status()).isEqualTo(401);
        assertThat(get(storePath(code), "X-Order-Service-Key", "detail-order-test").status()).isEqualTo(401);
        assertThat(get(orderPath(code), "X-Store-Service-Key", "detail-store-test").status()).isEqualTo(401);
        JsonNode paths = get("/v3/api-docs", null, null).body().path("paths");
        assertThat(paths.toString()).doesNotContain("/internal/");
    }
}
