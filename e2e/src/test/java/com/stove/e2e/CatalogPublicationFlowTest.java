package com.stove.e2e;

import static com.stove.e2e.ReviewJourney.*;

import static org.assertj.core.api.Assertions.*;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.Map;

@Tag("catalog")
class CatalogPublicationFlowTest {
    private static void approve(long project, Account creator, Account reviewer) {
        long review = request(project, creator, reviewer);
        assertThat(
                        Stove.gateway
                                .post(reviewPath(review) + "/approve", null, reviewer.headers())
                                .status())
                .isEqualTo(200);
        waitForResult(project, review, creator, "APPROVED");
    }

    private static long release(long project, Account creator) {
        var response =
                Stove.gateway.post(path(project) + "/release-requests", null, creator.headers());
        assertThat(response.status()).isEqualTo(202);
        return response.data().path("id").asLong();
    }

    private static void completed(long project, long release, Account creator) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> {
                            var history =
                                    Stove.gateway
                                            .get(
                                                    path(project) + "/release-requests",
                                                    creator.headers())
                                            .data();
                            var found = false;
                            for (var item : history)
                                if (item.path("id").asLong() == release) {
                                    assertThat(item.path("status").asText()).isEqualTo("COMPLETED");
                                    found = true;
                                }
                            assertThat(found).isTrue();
                        });
    }

    @Test
    void publishesOnlyApprovedRequestedSnapshotsAndReplacesAfterNewReview() throws Exception {
        var creator = account(true);
        var reviewer = login(REVIEWER_EMAIL, REVIEWER_PASSWORD);
        var p = project(creator);
        String code = data(p.id(), creator).path("productCode").asText();
        String detail = "/api/v1/products/by-code/" + code;
        assertThat(Stove.gateway.get(detail).status()).isEqualTo(404);
        assertThat(
                        Stove.gateway
                                .post(path(p.id()) + "/release-requests", null, creator.headers())
                                .status())
                .isEqualTo(409);
        approve(p.id(), creator, reviewer);
        assertThat(Stove.gateway.get(detail).status()).isEqualTo(404);
        long first = release(p.id(), creator);
        completed(p.id(), first, creator);
        var published = Stove.gateway.get(detail);
        assertThat(published.status()).isEqualTo(200);
        assertThat(published.data().path("title").asText()).isEqualTo("First title");
        assertThat(published.data().toString())
                .doesNotContain("storagePath", "ownerSubject", "releaseRequestId");
        assertThat(release(p.id(), creator)).isEqualTo(first);
        page(p.id(), creator, "Second title", 2000);
        assertThat(Stove.gateway.get(detail).data().path("title").asText())
                .isEqualTo("First title");
        assertThat(
                        Stove.gateway
                                .post(path(p.id()) + "/release-requests", null, creator.headers())
                                .status())
                .isEqualTo(409);
        approve(p.id(), creator, reviewer);
        long second = release(p.id(), creator);
        page(p.id(), creator, "Unreleased draft", 3000);
        completed(p.id(), second, creator);
        var current = Stove.gateway.get(detail).data();
        assertThat(current.path("title").asText()).isEqualTo("Second title");
        assertThat(current.path("price").asLong()).isEqualTo(2000);
        assertThat(current.path("productId")).isEqualTo(published.data().path("productId"));
        assertThat(current.path("publishedAt")).isEqualTo(published.data().path("publishedAt"));
        assertThat(data(p.id(), creator).path("releaseEligible").asBoolean()).isFalse();
        assertThat(Stove.gateway.get("/api/v1/products").data().path("totalElements").asLong())
                .isPositive();
        assertThat(Stove.gateway.get("/api/v1/products?size=101").status()).isEqualTo(400);
    }

    @Test
    void preparePendingResult() throws Exception {
        var creator = account(true);
        var reviewer = login(REVIEWER_EMAIL, REVIEWER_PASSWORD);
        var p = project(creator);
        approve(p.id(), creator, reviewer);
        long release = release(p.id(), creator);
        String code = data(p.id(), creator).path("productCode").asText();
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                assertThat(
                                                Stove.gateway
                                                        .get("/api/v1/products/by-code/" + code)
                                                        .status())
                                        .isEqualTo(200));
        assertThat(data(p.id(), creator).path("currentReleaseRequest").path("status").asText())
                .isEqualTo("REQUESTED");
        Files.writeString(
                Path.of(System.getProperty("stove.e2e.catalog-evidence")),
                JSON.writeValueAsString(
                        Map.of(
                                "email",
                                creator.email(),
                                "projectId",
                                p.id(),
                                "releaseRequestId",
                                release,
                                "code",
                                code)));
    }

    @Test
    void verifyPersistedResultAfterRestart() throws Exception {
        var saved =
                JSON.readTree(
                        Files.readString(
                                Path.of(System.getProperty("stove.e2e.catalog-evidence"))));
        var creator = login(saved.path("email").asText(), "creator-password-1234");
        completed(
                saved.path("projectId").asLong(), saved.path("releaseRequestId").asLong(), creator);
        assertThat(
                        Stove.gateway
                                .get("/api/v1/products/by-code/" + saved.path("code").asText())
                                .status())
                .isEqualTo(200);
        assertThat(release(saved.path("projectId").asLong(), creator))
                .isEqualTo(saved.path("releaseRequestId").asLong());
    }
}
