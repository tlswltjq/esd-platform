package com.stove.e2e;

import static com.stove.e2e.ReviewJourney.ADMIN_EMAIL;
import static com.stove.e2e.ReviewJourney.ADMIN_PASSWORD;
import static com.stove.e2e.ReviewJourney.Account;
import static com.stove.e2e.ReviewJourney.JSON;
import static com.stove.e2e.ReviewJourney.Project;
import static com.stove.e2e.ReviewJourney.REVIEWER_EMAIL;
import static com.stove.e2e.ReviewJourney.REVIEWER_PASSWORD;
import static com.stove.e2e.ReviewJourney.account;
import static com.stove.e2e.ReviewJourney.data;
import static com.stove.e2e.ReviewJourney.login;
import static com.stove.e2e.ReviewJourney.page;
import static com.stove.e2e.ReviewJourney.path;
import static com.stove.e2e.ReviewJourney.project;
import static com.stove.e2e.ReviewJourney.request;
import static com.stove.e2e.ReviewJourney.reviewPath;
import static com.stove.e2e.ReviewJourney.upload;
import static com.stove.e2e.ReviewJourney.waitForResult;
import static com.stove.e2e.ReviewJourney.zip;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.payload.ProjectReviewRejectedEvent;
import com.stove.common.kafka.contract.payload.ProjectReviewRequestedEvent;
import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@Tag("review")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReviewCreatorFlowTest {
    static Account creator, reviewer, admin;
    static Project project;
    static long first, second, oldVersion, current;
    static KafkaProducer<String, String> events;

    @BeforeAll
    static void setup() throws Exception {
        reviewer = login(REVIEWER_EMAIL, REVIEWER_PASSWORD);
        admin = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        creator = account(true);
        events =
                new KafkaProducer<>(
                        Map.of(
                                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                                System.getProperty("stove.e2e.kafka-brokers"),
                                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                                StringSerializer.class.getName(),
                                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                                StringSerializer.class.getName(),
                                ProducerConfig.ACKS_CONFIG,
                                "all"));
    }

    @AfterAll
    static void close() {
        if (events != null) events.close();
    }

    @Test
    @Order(1)
    void authAndGatewayProtectReviewerEndpoints() throws Exception {
        assertThat(Stove.gateway.get("/api/v1/reviews").status()).isEqualTo(401);
        assertThat(Stove.gateway.get("/api/v1/reviews", creator.headers()).status()).isEqualTo(403);
        assertThat(Stove.gateway.get("/api/v1/reviews", account(false).headers()).status())
                .isEqualTo(403);
        assertThat(Stove.gateway.get("/api/v1/reviews", reviewer.headers()).status())
                .isEqualTo(200);
        assertThat(Stove.gateway.get("/api/v1/reviews", admin.headers()).status()).isEqualTo(200);
        project = project(creator);
    }

    @Test
    @Order(2)
    void studioRequestsAreReceivedAsImmutableReviewSnapshots() {
        first = request(project.id(), creator, reviewer);
        E2eClient.Response response = Stove.gateway.get(reviewPath(first), reviewer.headers());
        assertThat(response.data().path("snapshot").path("ownerSubject").asText())
                .isEqualTo(creator.subject());
        assertThat(response.data().path("snapshot").path("title").asText())
                .isEqualTo("First title");
        assertThat(response.data().path("snapshot").has("storagePath")).isFalse();
        assertThat(
                        Stove.gateway
                                .post(
                                        path(project.id()) + "/release-requests",
                                        null,
                                        creator.headers())
                                .status())
                .isEqualTo(409);
    }

    @Test
    @Order(3)
    void reviewerCanDownloadOnlyTheSubmittedZip() throws Exception {
        assertThat(
                        Stove.gateway
                                .get(reviewPath(first) + "/build-download", creator.headers())
                                .status())
                .isEqualTo(403);
        E2eClient.Response response = Stove.gateway.get(reviewPath(first) + "/build-download", reviewer.headers());
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers().getFirst("Cache-Control")).contains("no-store");
        HttpResponse<byte[]> bytes =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create(response.data().path("url").asText()))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(bytes.statusCode()).isEqualTo(200);
        assertThat(bytes.body()).isEqualTo(project.zip());
        assertThat(
                        HexFormat.of()
                                .formatHex(
                                        MessageDigest.getInstance("SHA-256").digest(bytes.body())))
                .isEqualTo(response.data().path("checksum").asText());
    }

    @Test
    @Order(4)
    void rejectionIsFinalAndItsFeedbackReturnsToStudio() {
        E2eClient.Response rejected =
                Stove.gateway.post(
                        reviewPath(first) + "/reject",
                        Map.of("feedback", "Fix the description"),
                        reviewer.headers());
        assertThat(rejected.status()).isEqualTo(200);
        assertThat(rejected.data().path("actor").asText()).isEqualTo(reviewer.subject());
        assertThat(
                        Stove.gateway
                                .post(
                                        reviewPath(first) + "/reject",
                                        Map.of("feedback", "Fix the description"),
                                        admin.headers())
                                .data()
                                .path("actor")
                                .asText())
                .isEqualTo(reviewer.subject());
        assertThat(
                        Stove.gateway
                                .post(
                                        reviewPath(first) + "/reject",
                                        Map.of("feedback", "Changed reason"),
                                        reviewer.headers())
                                .status())
                .isEqualTo(409);
        assertThat(
                        Stove.gateway
                                .post(reviewPath(first) + "/approve", null, admin.headers())
                                .status())
                .isEqualTo(409);
        waitForResult(project.id(), first, creator, "REJECTED");
    }

    @Test
    @Order(5)
    void sameVersionRerequestCreatesANewReviewAndAdminCanApproveIt() {
        second = request(project.id(), creator, reviewer);
        assertThat(second).isNotEqualTo(first);
        assertThat(
                        Stove.gateway
                                .get(
                                        "/api/v1/reviews?projectId=" + project.id(),
                                        reviewer.headers())
                                .data()
                                .path("totalElements")
                                .asLong())
                .isEqualTo(2);
        E2eClient.Response approved = Stove.gateway.post(reviewPath(second) + "/approve", null, admin.headers());
        assertThat(approved.status()).isEqualTo(200);
        E2eClient.Response retry = Stove.gateway.post(reviewPath(second) + "/approve", null, reviewer.headers());
        assertThat(retry.data().path("actor")).isEqualTo(approved.data().path("actor"));
        assertThat(retry.data().path("decidedAt")).isEqualTo(approved.data().path("decidedAt"));
        waitForResult(project.id(), second, creator, "APPROVED");
    }

    @Test
    @Order(6)
    void realApprovalAllowsAnOutgoingReleaseRequest() {
        assertThat(data(project.id(), creator).path("releaseEligible").asBoolean()).isTrue();
        E2eClient.Response release =
                Stove.gateway.post(
                        path(project.id()) + "/release-requests", null, creator.headers());
        assertThat(release.status()).isEqualTo(202);
        assertThat(release.data().path("status").asText()).isEqualTo("REQUESTED");
        assertThat(release.data().path("reviewRequestId").asLong()).isEqualTo(second);
        assertThat(release.data().path("requestEventId").asText()).isNotBlank();
    }

    @Test
    @Order(7)
    void editingDuringReviewKeepsTheOldSnapshotAndCannotReuseItsApproval() throws Exception {
        page(project.id(), creator, "Second title", 2000);
        upload(project.id(), creator, "2", zip("second binary"));
        oldVersion = request(project.id(), creator, reviewer);
        page(project.id(), creator, "Third title", 3000);
        assertThat(
                        Stove.gateway
                                .get(reviewPath(oldVersion), reviewer.headers())
                                .data()
                                .path("snapshot")
                                .path("title")
                                .asText())
                .isEqualTo("Second title");
        assertThat(
                        Stove.gateway
                                .post(reviewPath(oldVersion) + "/approve", null, reviewer.headers())
                                .status())
                .isEqualTo(200);
        waitForResult(project.id(), oldVersion, creator, "APPROVED");
        assertThat(data(project.id(), creator).path("releaseEligible").asBoolean()).isFalse();
        assertThat(
                        Stove.gateway
                                .post(
                                        path(project.id()) + "/release-requests",
                                        null,
                                        creator.headers())
                                .status())
                .isEqualTo(409);
    }

    @Test
    @Order(8)
    void newVersionApprovalEnablesRereleaseWithoutReplacingHistory() {
        current = request(project.id(), creator, reviewer);
        assertThat(
                        Stove.gateway
                                .get(reviewPath(current), reviewer.headers())
                                .data()
                                .path("snapshot")
                                .path("title")
                                .asText())
                .isEqualTo("Third title");
        assertThat(
                        Stove.gateway
                                .post(reviewPath(current) + "/approve", null, reviewer.headers())
                                .status())
                .isEqualTo(200);
        waitForResult(project.id(), current, creator, "APPROVED");
        assertThat(
                        Stove.gateway
                                .post(
                                        path(project.id()) + "/release-requests",
                                        null,
                                        creator.headers())
                                .status())
                .isEqualTo(202);
        assertThat(
                        Stove.gateway
                                .get(
                                        "/api/v1/reviews?projectId=" + project.id(),
                                        reviewer.headers())
                                .data()
                                .path("totalElements")
                                .asLong())
                .isEqualTo(4);
    }

    @Test
    @Order(9)
    void duplicateRequestsAndLateOldResultsDoNotOverwriteFinalDecisions() throws Exception {
        JsonNode history =
                Stove.gateway
                        .get(path(project.id()) + "/review-requests", creator.headers())
                        .data();
        ProjectSnapshot snapshot = null;
        for (JsonNode r : history)
            if (r.path("id").asLong() == current)
                snapshot = JSON.readValue(r.path("snapshot").asText(), ProjectSnapshot.class);
        assertThat(snapshot).isNotNull();
        ProjectReviewRequestedEvent duplicate = ProjectReviewRequestedEvent.of(current, snapshot);
        send(duplicate);
        send(
                ProjectReviewRejectedEvent.of(
                        oldVersion,
                        project.id(),
                        Stove.gateway
                                .get(reviewPath(oldVersion), reviewer.headers())
                                .data()
                                .path("snapshot")
                                .path("contentVersion")
                                .asLong(),
                        snapshot.productCode(),
                        "Late rejected result",
                        "late-reviewer"));
        Awaitility.await()
                .during(Duration.ofSeconds(1))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            assertThat(
                                            Stove.gateway
                                                    .get(reviewPath(current), reviewer.headers())
                                                    .data()
                                                    .path("status")
                                                    .asText())
                                    .isEqualTo("APPROVED");
                            assertThat(
                                            data(project.id(), creator)
                                                    .path("currentReviewRequest")
                                                    .path("id")
                                                    .asLong())
                                    .isEqualTo(current);
                            assertThat(
                                            data(project.id(), creator)
                                                    .path("releaseEligible")
                                                    .asBoolean())
                                    .isTrue();
                        });
    }

    @Test
    @Order(10)
    void apiContractContainsOnlyTheFivePlannedOperations() {
        E2eClient.Response docs = Stove.gateway.get("/v3/api-docs/review");
        assertThat(docs.status()).isEqualTo(200);
        ArrayList<String> businessPaths = new ArrayList<String>();
        docs.body()
                .path("paths")
                .fieldNames()
                .forEachRemaining(
                        p -> {
                            if (p.startsWith("/api/v1/reviews")) businessPaths.add(p);
                        });
        assertThat(businessPaths).hasSize(5);
        assertThat(docs.body().path("paths").has("/api/v1/reviews/cases")).isFalse();
        assertThat(
                        Stove.gateway
                                .post(
                                        reviewPath(current) + "/reject",
                                        Map.of("feedback", "Fix", "actor", "spoofed"),
                                        reviewer.headers())
                                .status())
                .isEqualTo(400);
    }

    static void send(DomainEvent e) throws Exception {
        ProducerRecord<String, String> record =
                new ProducerRecord<String, String>(
                        e.topic(), e.partitionKey(), JSON.writeValueAsString(e));
        record.headers()
                .add(
                        EventHeaders.EVENT_ID,
                        e.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers()
                .add(
                        EventHeaders.EVENT_TYPE,
                        e.eventType().getBytes(StandardCharsets.UTF_8));
        events.send(record).get(10, TimeUnit.SECONDS);
    }
}
