package com.stove.e2e;

import static com.stove.e2e.ReviewJourney.*;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.Map;

/** Each method is run in its own JVM on opposite sides of a real Review process restart. */
@Tag("review-restart")
class ReviewRestartFlowTest {
    private Path evidence() {
        return Path.of(System.getProperty("stove.e2e.review-evidence"));
    }

    @Test
    void preparePendingResult() throws Exception {
        var creator = account(true);
        var reviewer = login(REVIEWER_EMAIL, REVIEWER_PASSWORD);
        var project = project(creator);
        long id = request(project.id(), creator, reviewer);
        var approved = Stove.gateway.post(reviewPath(id) + "/approve", null, reviewer.headers());
        assertThat(approved.status()).isEqualTo(200);
        assertThat(data(project.id(), creator).path("currentReviewRequest").path("status").asText())
                .isEqualTo("REQUESTED");
        Files.writeString(
                evidence(),
                JSON.writeValueAsString(
                        Map.of(
                                "email",
                                creator.email(),
                                "projectId",
                                project.id(),
                                "reviewRequestId",
                                id,
                                "actor",
                                reviewer.subject(),
                                "decidedAt",
                                approved.data().path("decidedAt").asText())));
    }

    @Test
    void verifyPersistedResultAfterRestart() throws Exception {
        var saved = JSON.readTree(Files.readString(evidence()));
        var creator = login(saved.path("email").asText(), "creator-password-1234");
        var reviewer = login(REVIEWER_EMAIL, REVIEWER_PASSWORD);
        long id = saved.path("reviewRequestId").asLong(),
                project = saved.path("projectId").asLong();
        var review = Stove.gateway.get(reviewPath(id), reviewer.headers());
        assertThat(review.data().path("status").asText()).isEqualTo("APPROVED");
        assertThat(review.data().path("actor").asText()).isEqualTo(saved.path("actor").asText());
        assertThat(review.data().path("decidedAt").asText())
                .isEqualTo(saved.path("decidedAt").asText());
        waitForResult(project, id, creator, "APPROVED");
        assertThat(
                        Stove.gateway
                                .post(reviewPath(id) + "/approve", null, reviewer.headers())
                                .data()
                                .path("decidedAt")
                                .asText())
                .isEqualTo(saved.path("decidedAt").asText());
        assertThat(data(project, creator).path("releaseEligible").asBoolean()).isTrue();
    }
}
