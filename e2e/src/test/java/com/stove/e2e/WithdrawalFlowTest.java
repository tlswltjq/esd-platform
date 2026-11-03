package com.stove.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Catalog is restarted between the two phases; only its automatic worker is paused initially. */
@Tag("withdrawal")
class WithdrawalFlowTest {
    private static final String JOBS = "/api/v1/admin/catalog-release-jobs/";

    @Test
    void prepareQueuedRelease() throws Exception {
        ReviewJourney.Account creator = ReviewJourney.account(true);
        ReviewJourney.Account other = ReviewJourney.account(true);
        ReviewJourney.Account reviewer = ReviewJourney.login(ReviewJourney.REVIEWER_EMAIL, ReviewJourney.REVIEWER_PASSWORD);
        ReviewJourney.Account admin = ReviewJourney.login(ReviewJourney.ADMIN_EMAIL, ReviewJourney.ADMIN_PASSWORD);
        long project = ReviewJourney.project(creator).id();
        String path = ReviewJourney.path(project);
        String code = ReviewJourney.data(project, creator).path("productCode").asText();
        Map<String, Object> original = ReviewJourney.submission(path, creator.headers());
        long first = submit(path + "/review-requests", original, creator);
        String cancelPath = path + "/review-requests/" + first + "/withdrawals";
        Map<String, Object> cancel = Map.of("requestId", "withdraw-first");
        long receipt = submit(cancelPath, cancel, creator);
        resolved(cancelPath, receipt, creator);
        assertThat(Stove.gateway.get(cancelPath + "/" + receipt, other.headers()).status()).isEqualTo(403);
        assertThat(Stove.gateway.get(cancelPath).status()).isEqualTo(401);
        assertThat(submit(cancelPath, cancel, creator)).isEqualTo(receipt);
        assertThat(submit(path + "/review-requests", original, creator)).isEqualTo(first);
        ReviewJourney.waitForResult(project, first, creator, "WITHDRAWN");
        assertThat(Stove.gateway.get(ReviewJourney.reviewPath(first), reviewer.headers()).data().path("status").asText())
                .isEqualTo("WITHDRAWN");
        assertThat(Stove.gateway.post(ReviewJourney.reviewPath(first) + "/approve", null, reviewer.headers()).status()).isEqualTo(409);

        // The unchanged snapshot may be reviewed again, but a pending older version blocks a third request.
        long second = ReviewJourney.request(project, creator, reviewer);
        assertThat(second).isNotEqualTo(first);
        ReviewJourney.page(project, creator, "Reviewed title", 2000);
        JsonNode current = ReviewJourney.data(project, creator);
        assertThat(current.path("activeReviewRequest").path("id").asLong()).isEqualTo(second);
        assertThat(current.path("currentReviewRequest").isNull()).isTrue();
        assertThat(Stove.gateway.post(path + "/review-requests", ReviewJourney.submission(path, creator.headers()), creator.headers()).status())
                .isEqualTo(409);
        String secondCancel = path + "/review-requests/" + second + "/withdrawals";
        long secondReceipt = submit(secondCancel, Map.of("requestId", "withdraw-second", "reason", "Description corrected"), creator);
        resolved(secondCancel, secondReceipt, creator);
        long approved = ReviewJourney.request(project, creator, reviewer);
        assertThat(Stove.gateway.post(ReviewJourney.reviewPath(approved) + "/approve", null, reviewer.headers()).status()).isEqualTo(200);
        ReviewJourney.waitForResult(project, approved, creator, "APPROVED");
        assertThat(Stove.gateway.get("/api/v1/products/by-code/" + code).status()).isEqualTo(404);

        Map<String, Object> firstIntent = ReviewJourney.submission(path, creator.headers());
        long firstRelease = submit(path + "/release-requests", firstIntent, creator);
        queued(firstRelease, admin);
        assertThat(Stove.gateway.get(JOBS + firstRelease, creator.headers()).status()).isEqualTo(403);
        assertThat(Stove.gateway.get(JOBS + firstRelease).status()).isEqualTo(401);
        String releaseCancel = path + "/release-requests/" + firstRelease + "/withdrawals";
        long releaseReceipt = submit(releaseCancel, Map.of("requestId", "withdraw-release"), creator);
        resolved(releaseCancel, releaseReceipt, creator);
        assertThat(submit(path + "/release-requests", firstIntent, creator)).isEqualTo(firstRelease);
        assertThat(Stove.gateway.get("/api/v1/products/by-code/" + code).status()).isEqualTo(404);
        long nextRelease = submit(path + "/release-requests", ReviewJourney.submission(path, creator.headers()), creator);
        assertThat(nextRelease).isNotEqualTo(firstRelease);
        queued(nextRelease, admin);
        assertThat(ReviewJourney.data(project, creator).path("currentReviewRequest").path("id").asLong()).isEqualTo(approved);
        ReviewJourney.page(project, creator, "Next update", 3000);
        long nextReview = ReviewJourney.request(project, creator, reviewer);
        assertThat(Stove.gateway.post(ReviewJourney.reviewPath(nextReview) + "/approve", null, reviewer.headers()).status()).isEqualTo(200);
        ReviewJourney.waitForResult(project, nextReview, creator, "APPROVED");
        assertThat(ReviewJourney.data(project, creator).path("activeReleaseRequest").path("id").asLong()).isEqualTo(nextRelease);
        assertThat(Stove.gateway.post(path + "/release-requests", ReviewJourney.submission(path, creator.headers()), creator.headers()).status())
                .isEqualTo(409);
        Path evidence = evidence();
        Files.createFile(evidence, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(evidence, ReviewJourney.JSON.writeValueAsString(Map.of(
                "email", creator.email(), "project", project, "code", code, "review", approved,
                "withdrawnRelease", firstRelease, "queuedRelease", nextRelease, "firstIntent", firstIntent, "nextReview", nextReview)));
    }

    @Test
    void publishesOnlyTheSurvivingRequestAfterRestart() throws Exception {
        JsonNode saved = ReviewJourney.JSON.readTree(Files.readString(evidence()));
        ReviewJourney.Account creator = ReviewJourney.login(saved.path("email").asText(), "creator-password-1234");
        ReviewJourney.Account admin = ReviewJourney.login(ReviewJourney.ADMIN_EMAIL, ReviewJourney.ADMIN_PASSWORD);
        long project = saved.path("project").asLong();
        long withdrawn = saved.path("withdrawnRelease").asLong();
        long surviving = saved.path("queuedRelease").asLong();
        String path = ReviewJourney.path(project);
        Awaitility.await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
            assertThat(Stove.gateway.get(JOBS + surviving, admin.headers()).data().path("state").asText()).isEqualTo("COMPLETED");
            assertThat(ReviewJourney.data(project, creator).path("activeReleaseRequest").isNull()).isTrue();
        });
        JsonNode history = ReviewJourney.listItems(path + "/release-requests", creator.headers());
        assertThat(history).anySatisfy(item -> {
            assertThat(item.path("id").asLong()).isEqualTo(withdrawn);
            assertThat(item.path("status").asText()).isEqualTo("WITHDRAWN");
        });
        JsonNode published = Stove.gateway.get("/api/v1/products/by-code/" + saved.path("code").asText()).data();
        assertThat(published.path("title").asText()).isEqualTo("Reviewed title");
        assertThat(published.path("price").asLong()).isEqualTo(2000);
        assertThat(ReviewJourney.data(project, creator).path("currentReviewRequest").path("id").asLong()).isEqualTo(saved.path("nextReview").asLong());
        assertThat(submit(path + "/release-requests", saved.path("firstIntent"), creator)).isEqualTo(withdrawn);
        assertThat(Stove.gateway.post(path + "/release-requests/" + surviving + "/withdrawals",
                Map.of("requestId", UUID.randomUUID().toString()), creator.headers()).status()).isEqualTo(409);
        assertThat(Stove.gateway.get(JOBS + withdrawn, admin.headers()).data().path("state").asText()).isEqualTo("COMPLETED");
        long update = submit(path + "/release-requests", ReviewJourney.submission(path, creator.headers()), creator);
        assertThat(update).isNotEqualTo(surviving);
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(ReviewJourney.data(project, creator).path("currentReleaseRequest").path("status").asText()).isEqualTo("COMPLETED");
            assertThat(Stove.gateway.get("/api/v1/products/by-code/" + saved.path("code").asText()).data().path("title").asText()).isEqualTo("Next update");
        });
    }

    private long submit(String path, Object body, ReviewJourney.Account creator) {
        E2eClient.Response response = Stove.gateway.post(path, body, creator.headers());
        assertThat(response.status()).as(path).isEqualTo(202);
        return response.data().path("id").asLong();
    }

    private void resolved(String path, long receipt, ReviewJourney.Account creator) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            E2eClient.Response response = Stove.gateway.get(path + "/" + receipt, creator.headers());
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.data().path("state").asText()).isEqualTo("WITHDRAWN");
            assertThat(response.data().path("finalizedAt").isTextual()).isTrue();
        });
    }

    private void queued(long id, ReviewJourney.Account admin) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            E2eClient.Response response = Stove.gateway.get(JOBS + id, admin.headers());
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.data().path("state").asText()).isEqualTo("PENDING");
            assertThat(response.data().toString()).doesNotContain("storagePath", "snapshot");
        });
    }

    private Path evidence() { return Path.of(System.getProperty("stove.e2e.withdrawal-evidence")); }
}
