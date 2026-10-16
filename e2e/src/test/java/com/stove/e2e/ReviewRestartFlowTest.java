package com.stove.e2e;

import static com.stove.e2e.ReviewJourney.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
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
        ReviewJourney.Account creator = account(true);
        ReviewJourney.Account reviewer = login(REVIEWER_EMAIL, REVIEWER_PASSWORD);
        ReviewJourney.Project project = project(creator);
        long id = request(project.id(), creator, reviewer);
        E2eClient.Response approved = Stove.gateway.post(reviewPath(id) + "/approve", null, reviewer.headers());
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
        JsonNode saved = JSON.readTree(Files.readString(evidence()));
        ReviewJourney.Account creator = login(saved.path("email").asText(), "creator-password-1234");
        ReviewJourney.Account reviewer = login(REVIEWER_EMAIL, REVIEWER_PASSWORD);
        long id = saved.path("reviewRequestId").asLong(),
                project = saved.path("projectId").asLong();
        E2eClient.Response review = Stove.gateway.get(reviewPath(id), reviewer.headers());
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
