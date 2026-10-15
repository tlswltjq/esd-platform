package com.stove.e2e;

import static com.stove.e2e.ReviewJourney.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;

@Tag("store")
class StorePublicationFlowTest {
    private static Path evidence() { return Path.of(System.getProperty("stove.e2e.store-evidence")); }
    private static void publish(long project, Account creator, Account reviewer) {
        long review = request(project, creator, reviewer);
        assertThat(Stove.gateway.post(reviewPath(review) + "/approve", null, reviewer.headers()).status()).isEqualTo(200);
        waitForResult(project, review, creator, "APPROVED");
        assertThat(Stove.gateway.post(path(project) + "/release-requests", null, creator.headers()).status()).isEqualTo(202);
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(data(project, creator).path("currentReleaseRequest").path("status").asText())
                        .isEqualTo("COMPLETED"));
    }
    private static void matchesCatalog(String code) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var store = Stove.gateway.get("/api/v1/storefront/products/" + code);
            var catalog = Stove.gateway.get("/api/v1/products/by-code/" + code);
            assertThat(store.status()).isEqualTo(200);
            assertThat(catalog.status()).isEqualTo(200);
            assertThat(store.data()).isEqualTo(catalog.data());
        });
    }
    @Test void publishesSearchesAndReplacesOnlyReleasedSnapshots() throws Exception {
        var creator = account(true);
        var reviewer = login(REVIEWER_EMAIL, REVIEWER_PASSWORD);
        var project = project(creator);
        String code = data(project.id(), creator).path("productCode").asText();
        String detail = "/api/v1/storefront/products/" + code;
        assertThat(Stove.gateway.get(detail).status()).isEqualTo(404);
        publish(project.id(), creator, reviewer);
        matchesCatalog(code);
        var first = Stove.gateway.get(detail).data();
        assertThat(first.path("title").asText()).isEqualTo("First title");
        var search = Stove.gateway.get("/api/v1/storefront/products?q=First&sort=PRICE_ASC&size=1");
        assertThat(search.status()).isEqualTo(200);
        assertThat(search.data().path("totalElements").asLong()).isPositive();
        page(project.id(), creator, "Store second title", 2000);
        assertThat(Stove.gateway.get(detail).data()).isEqualTo(first);
        publish(project.id(), creator, reviewer);
        matchesCatalog(code);
        page(project.id(), creator, "Unreleased draft", 3000);
        var current = Stove.gateway.get(detail).data();
        assertThat(current.path("title").asText()).isEqualTo("Store second title");
        assertThat(current.path("price").asLong()).isEqualTo(2000);
        assertThat(current.path("publishedAt")).isEqualTo(first.path("publishedAt"));
        assertThat(current.toString()).doesNotContain("storagePath", "ownerSubject", "releaseRequestId");
        Files.writeString(evidence(), JSON.writeValueAsString(Map.of("code", code, "product", current)));
    }
    @Test void verifiesHttpBootstrapWithKafkaListenerDisabled() throws Exception {
        var saved = JSON.readTree(Files.readString(evidence()));
        String code = saved.path("code").asText();
        matchesCatalog(code);
        assertThat(Stove.gateway.get("/api/v1/storefront/products/" + code).data()).isEqualTo(saved.path("product"));
    }
    @Test void servesStoredSnapshotWithoutCatalog() throws Exception {
        var saved = JSON.readTree(Files.readString(evidence()));
        var detail = Stove.gateway.get("/api/v1/storefront/products/" + saved.path("code").asText());
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.data()).isEqualTo(saved.path("product"));
        assertThat(Stove.gateway.get("/api/v1/storefront/products").status()).isEqualTo(200);
    }
    @Test void failedBootstrapRemainsUnavailable() {
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var result = Stove.gateway.get("/api/v1/storefront/products");
            assertThat(result.status()).isEqualTo(503);
            assertThat(result.body().path("error").path("code").asText()).isEqualTo("STORE_NOT_READY");
        });
    }
}
