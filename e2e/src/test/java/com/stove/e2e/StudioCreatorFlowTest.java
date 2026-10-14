package com.stove.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.*;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.payload.*;

import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

import javax.imageio.ImageIO;

/** Real Auth/Gateway/Studio; unfinished external decisions enter only through event fixtures. */
@Tag("studio")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StudioCreatorFlowTest {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final String CODE = "STUDIO-" + UUID.randomUUID();
    private static KafkaProducer<String, String> events;
    private static Map<String, String> creator;
    private static String owner;
    private static long projectId;
    private static long firstBuild;
    private static long firstReleaseRequest;
    private static String thumbnail;
    private static JsonNode currentReview;
    private static long nextBuild;

    @BeforeAll
    static void eventBoundary() {
        String brokers = System.getProperty("stove.e2e.kafka-brokers", "");
        if (brokers.isBlank())
            throw new IllegalStateException("stove.e2e.kafka-brokers is required");
        events =
                new KafkaProducer<>(
                        Map.of(
                                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                                brokers,
                                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                                StringSerializer.class.getName(),
                                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                                StringSerializer.class.getName(),
                                ProducerConfig.ACKS_CONFIG,
                                "all"));
    }

    @AfterAll
    static void closeEvents() {
        if (events != null) events.close();
    }

    @Test
    @Order(1)
    void authClaimsAndGatewayPermissionsIdentifyTheProjectOwner() throws Exception {
        String email = "creator-" + UUID.randomUUID() + "@studio.local";
        String password = "creator-password-1234";
        var signup =
                Stove.gateway.post(
                        "/api/v1/auth/signup", Map.of("email", email, "password", password));
        assertThat(signup.status()).as("%s", signup).isEqualTo(200);
        owner = signup.data().path("subject").asText();
        String token = OidcLogin.token(email, password);
        var claims = JSON.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertThat(claims.path("sub").asText()).isEqualTo(owner);
        assertThat(claims.path("aud").toString()).contains("esd-api");
        assertThat(claims.path("roles").toString()).contains("CREATOR");
        creator = Map.of("Authorization", "Bearer " + token);
        var anonymous =
                Stove.gateway.post(
                        "/api/v1/studio/projects", Map.of("productCode", CODE, "title", "Game"));
        assertThat(anonymous.status()).as("Gateway CSRF rejects an anonymous POST").isEqualTo(403);
        var member = account("/api/v1/auth/signup/member");
        assertThat(
                        Stove.gateway
                                .post(
                                        "/api/v1/studio/projects",
                                        Map.of("productCode", CODE, "title", "Game"),
                                        member)
                                .status())
                .isEqualTo(403);
        var created =
                Stove.gateway.post(
                        "/api/v1/studio/projects",
                        Map.of("productCode", CODE, "title", "Game"),
                        creator);
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        projectId = created.data().path("id").asLong();
        assertThat(projectId).isPositive();
        assertThat(
                        Stove.gateway
                                .post(
                                        "/api/v1/studio/projects",
                                        Map.of("productCode", CODE, "title", "Duplicate"),
                                        creator)
                                .status())
                .isEqualTo(409);
        var docs = Stove.gateway.get("/v3/api-docs/studio");
        assertThat(docs.status()).as("%s", docs).isEqualTo(200);
        assertThat(docs.body().toString()).doesNotContain("/api/v1/studio/workspace");
    }

    @Test
    @Order(2)
    void entireZipThumbnailAndStorePageAreRegisteredThroughGateway() throws Exception {
        assertThat(Stove.gateway.post(project() + "/review-requests", null, creator).status())
                .isEqualTo(409);
        var image = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", image);
        var uploaded =
                Stove.gateway.postMultipart(
                        project() + "/thumbnail",
                        Map.of(),
                        image.toByteArray(),
                        "thumbnail.png",
                        "image/png",
                        creator);
        assertThat(uploaded.status()).as("%s", uploaded).isEqualTo(201);
        thumbnail = uploaded.data().path("url").asText();
        var invalid =
                Stove.gateway.postMultipart(
                        project() + "/builds",
                        Map.of("version", "bad"),
                        "bad".getBytes(),
                        "bad.zip",
                        "application/zip",
                        creator);
        assertThat(invalid.status()).isEqualTo(400);
        var build = upload("1");
        firstBuild = build.path("id").asLong();
        assertThat(build.path("checksum").asText()).hasSize(64);
        assertThat(build.path("fileSize").asLong()).isPositive();
        assertThat(
                        Stove.gateway
                                .postMultipart(
                                        project() + "/builds",
                                        Map.of("version", "1"),
                                        zip("duplicate"),
                                        "build.zip",
                                        "application/zip",
                                        creator)
                                .status())
                .isEqualTo(409);
        savePage("First title", 1000);
        assertThat(data().path("currentBuildId").asLong()).isEqualTo(firstBuild);
    }

    @Test
    @Order(3)
    void reviewRequestFreezesTheAuthSubjectAndSupportsRejectionAndResubmission() throws Exception {
        currentReview = requestReview();
        var snapshot = JSON.readTree(currentReview.path("snapshot").asText());
        assertThat(snapshot.path("ownerSubject").asText()).isEqualTo(owner);
        assertThat(snapshot.has("sellerId")).isFalse();
        assertThat(requestReview().path("id").asLong())
                .isEqualTo(currentReview.path("id").asLong());
        send(
                ProjectReviewRejectedEvent.of(
                        reviewRequestId(),
                        projectId,
                        reviewVersion(),
                        CODE,
                        "Fix description",
                        "fixture-reviewer"));
        awaitStatus("REJECTED");
        var history = Stove.gateway.get(project() + "/review-requests", creator);
        assertThat(history.data().get(0).path("feedback").asText()).isEqualTo("Fix description");
        long previous = reviewRequestId();
        savePage("First title", 1000);
        currentReview = requestReview();
        assertThat(reviewRequestId()).isNotEqualTo(previous);
    }

    @Test
    @Order(4)
    void externalApprovalAllowsOnlyAnOutgoingReleaseRequest() throws Exception {
        assertThat(Stove.gateway.post(project() + "/release-requests", null, creator).status())
                .isEqualTo(409);
        reportReviewApproval();
        assertThat(data().path("releaseEligible").asBoolean()).isTrue();
        var request = requestRelease();
        firstReleaseRequest = request.path("id").asLong();
        assertThat(requestRelease().path("id").asLong()).isEqualTo(firstReleaseRequest);
        assertThat(request.path("status").asText()).isEqualTo("REQUESTED");
        reportReleaseCompletion(request);
        awaitReleaseResult("COMPLETED");
        assertThat(data().has("published")).isFalse();
        assertThat(data().has("status")).isFalse();
        var history = Stove.gateway.get(project() + "/release-requests", creator);
        assertThat(history.data().get(0).path("id").asLong()).isEqualTo(firstReleaseRequest);
    }

    @Test
    @Order(5)
    void changingDetailsAndBuildRequiresAReviewRequestForTheNewVersion() throws Exception {
        var before = data();
        savePage("Second title", 2000);
        assertThat(
                        Stove.gateway
                                .put(
                                        project() + "/store-page",
                                        page(
                                                "Stale title",
                                                1000,
                                                before.path("contentVersion").asLong()),
                                        creator)
                                .status())
                .isEqualTo(409);
        nextBuild = upload("2").path("id").asLong();
        assertThat(data().path("releaseEligible").asBoolean()).isFalse();
        assertThat(Stove.gateway.post(project() + "/release-requests", null, creator).status())
                .isEqualTo(409);
        assertThat(
                        Stove.gateway
                                .get(project() + "/release-requests", creator)
                                .data()
                                .get(0)
                                .path("id")
                                .asLong())
                .isEqualTo(firstReleaseRequest);
        assertThat(JSON.readTree(currentReview.path("snapshot").asText()).path("title").asText())
                .isEqualTo("First title");
        currentReview = requestReview();
        reportReviewApproval();
        assertThat(JSON.readTree(currentReview.path("snapshot").asText()).path("buildId").asLong())
                .isEqualTo(nextBuild);
    }

    @Test
    @Order(6)
    void rejectionAndRerequestKeepSeparateRequestHistoryAndIgnoreLateOldResults() throws Exception {
        var failed = requestRelease();
        send(
                ProjectReleaseRejectedEvent.of(
                        failed.path("id").asLong(),
                        projectId,
                        reviewVersion(),
                        CODE,
                        "External rejection",
                        failed.path("requestEventId").asText()));
        awaitReleaseResult("REJECTED");
        var retry = requestRelease();
        assertThat(retry.path("id").asLong()).isNotEqualTo(failed.path("id").asLong());
        reportReleaseCompletion(failed);
        reportReleaseCompletion(retry);
        awaitReleaseResult("COMPLETED");
        var history = Stove.gateway.get(project() + "/release-requests", creator).data();
        assertThat(history.size()).isEqualTo(3);
        assertThat(history.get(0).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(history.get(1).path("status").asText()).isEqualTo("REJECTED");
        assertThat(data().path("storePage").path("title").asText()).isEqualTo("Second title");
    }

    @Test
    @Order(7)
    void anotherCreatorCannotReadOrChangeTheOwnedProject() {
        var other = account("/api/v1/auth/signup");
        assertThat(Stove.gateway.get(project(), other).status()).isEqualTo(403);
        assertThat(
                        Stove.gateway
                                .put(
                                        project() + "/store-page",
                                        page("Stolen", 0, data().path("contentVersion").asLong()),
                                        other)
                                .status())
                .isEqualTo(403);
        assertThat(Stove.gateway.post(project() + "/review-requests", null, other).status())
                .isEqualTo(403);
        assertThat(Stove.gateway.post(project() + "/release-requests", null, other).status())
                .isEqualTo(403);
    }

    private static Map<String, String> account(String signupPath) {
        String email = "account-" + UUID.randomUUID() + "@studio.local",
                password = "account-password-1234";
        var signup = Stove.gateway.post(signupPath, Map.of("email", email, "password", password));
        assertThat(signup.status()).as("%s", signup).isEqualTo(200);
        return Map.of("Authorization", "Bearer " + OidcLogin.token(email, password));
    }

    private static String project() {
        return "/api/v1/studio/projects/" + projectId;
    }

    private static JsonNode data() {
        return Stove.gateway.get(project(), creator).data();
    }

    private static long reviewRequestId() {
        return currentReview.path("id").asLong();
    }

    private static long reviewVersion() {
        return currentReview.path("contentVersion").asLong();
    }

    private static Map<String, Object> page(String title, long price, long version) {
        return Map.of(
                "contentVersion",
                version,
                "title",
                title,
                "shortDescription",
                "Summary",
                "thumbnailUrl",
                thumbnail,
                "content",
                "Description",
                "price",
                price);
    }

    private static void savePage(String title, long price) {
        var updated =
                Stove.gateway.put(
                        project() + "/store-page",
                        page(title, price, data().path("contentVersion").asLong()),
                        creator);
        assertThat(updated.status()).as("%s", updated).isEqualTo(200);
    }

    private static JsonNode upload(String version) throws IOException {
        var response =
                Stove.gateway.postMultipart(
                        project() + "/builds",
                        Map.of("version", version),
                        zip(version),
                        "game.zip",
                        "application/zip",
                        creator);
        assertThat(response.status()).as("%s", response).isEqualTo(201);
        return response.data();
    }

    private static byte[] zip(String version) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("game.bin"));
            zip.write(version.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static JsonNode requestReview() {
        var request = Stove.gateway.post(project() + "/review-requests", null, creator);
        assertThat(request.status()).as("%s", request).isEqualTo(202);
        return request.data();
    }

    private static void reportReviewApproval() throws Exception {
        send(
                ProjectReviewApprovedEvent.of(
                        reviewRequestId(), projectId, reviewVersion(), CODE, "fixture-reviewer"));
        awaitStatus("APPROVED");
    }

    private static JsonNode requestRelease() {
        var response = Stove.gateway.post(project() + "/release-requests", null, creator);
        assertThat(response.status()).as("%s", response).isEqualTo(202);
        return response.data();
    }

    private static void reportReleaseCompletion(JsonNode request) throws Exception {
        send(
                ProjectReleaseCompletedEvent.of(
                        request.path("id").asLong(),
                        projectId,
                        request.path("contentVersion").asLong(),
                        CODE,
                        request.path("requestEventId").asText()));
    }

    private static void awaitStatus(String status) {
        Await.untilResponse(
                "Review request result " + status,
                () -> Stove.gateway.get(project(), creator),
                r -> status.equals(r.data().path("currentReviewRequest").path("status").asText()));
    }

    private static void awaitReleaseResult(String status) {
        Await.untilResponse(
                "Release request result " + status,
                () -> Stove.gateway.get(project(), creator),
                r -> status.equals(r.data().path("currentReleaseRequest").path("status").asText()));
    }

    private static void send(DomainEvent event) throws Exception {
        var record =
                new ProducerRecord<String, String>(
                        event.topic(), event.partitionKey(), JSON.writeValueAsString(event));
        record.headers()
                .add(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers()
                .add(EventHeaders.EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8));
        record.headers()
                .add(
                        EventHeaders.OCCURRED_AT,
                        event.occurredAt().toString().getBytes(StandardCharsets.UTF_8));
        events.send(record).get(20, TimeUnit.SECONDS);
    }
}
