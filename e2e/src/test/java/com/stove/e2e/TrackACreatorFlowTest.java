package com.stove.e2e;

import static com.stove.e2e.Journey.PRICE;
import static com.stove.e2e.Journey.PRODUCT_CODE;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.stove.e2e.E2eClient.Response;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/** P0 전체 경로: OIDC → CI 업로드 → 검증 → 수정 재제출 → 출시 → rollback. */
@Order(1)
@DisplayName("트랙 A — 셀프 퍼블리싱 P0")
class TrackACreatorFlowTest {

    private static final String CREATOR_EMAIL = "creator-" + Journey.STAMP + "@e2e.local";
    private static final String CREATOR_PASSWORD = "creator-password-" + Journey.STAMP;
    private static String machineCredential;
    private static long build1;
    private static long build2;
    private static long metadataRevision;
    private static long pricingRevision;
    private static long ratingRevision;
    private static long release1;

    @Test
    @Order(1)
    @DisplayName("OIDC 가입·PKCE 로그인 후 개인 Workspace의 프로젝트를 만든다")
    void authenticatesAndCreatesProject() {
        Response signup = Stove.auth.post("/api/v1/auth/signup", Map.of(
                "email", CREATOR_EMAIL, "password", CREATOR_PASSWORD));
        assertThat(signup.status()).as("%s", signup).isEqualTo(200);

        Journey.creatorToken(OidcLogin.token(CREATOR_EMAIL, CREATOR_PASSWORD));
        String reviewerPassword = System.getenv().getOrDefault(
                "AUTH_REVIEWER_PASSWORD", "reviewer-local-only");
        Journey.reviewerToken(OidcLogin.token("reviewer@esd.local", reviewerPassword));

        Response created = Stove.gateway.post("/api/v1/studio/games", Map.of(
                "productCode", PRODUCT_CODE,
                "title", Journey.PRODUCT_TITLE,
                "price", PRICE,
                "currency", "KRW"), Journey.asCreator());
        assertThat(created.status()).as("%s", created).isEqualTo(200);
        Journey.gameId(created.data().path("gameId").asLong());

        Response credential = Stove.gateway.post(
                "/api/v1/studio/projects/%d/credentials".formatted(Journey.gameId()),
                Map.of("name", "e2e-ci"), Journey.asCreator());
        assertThat(credential.status()).as("%s", credential).isEqualTo(200);
        machineCredential = credential.data().path("token").asText();
        assertThat(machineCredential).startsWith("esd_ci_");
    }

    @Test
    @Order(2)
    @DisplayName("외부 CI credential로 패키지를 multipart 업로드하고 VALIDATED를 기다린다")
    void uploadsAndValidatesBuild() throws Exception {
        build1 = uploadBuild("1.0.0", "100");
    }

    @Test
    @Order(3)
    @DisplayName("불변 revision을 제출하고 변경 요청 뒤 새 revision으로 재제출한다")
    void requestsChangesAndResubmits() {
        metadataRevision = revision("store-page-revisions", Map.of(
                "title", Journey.PRODUCT_TITLE,
                "shortDescription", "첫 심사용 소개",
                "platform", "WINDOWS",
                "minimumRequirements", "Windows 10"));
        pricingRevision = revision("pricing-revisions", Map.of("price", PRICE));
        ratingRevision = revision("rating-revisions", Map.of(
                "questionnaire", Map.of("adultContent", false, "cashGambling", false)));

        long firstSubmission = submit(build1, metadataRevision);
        long storeCase = reviewCase(firstSubmission, "STORE_PAGE");
        Response changes = Stove.gateway.post(
                "/api/v1/reviews/cases/%d/changes-requested".formatted(storeCase),
                Map.of("reasonCode", "METADATA", "feedback", "소개를 구체화해 주세요."),
                Journey.asReviewer());
        assertThat(changes.status()).as("%s", changes).isEqualTo(200);

        metadataRevision = revision("store-page-revisions", Map.of(
                "title", Journey.PRODUCT_TITLE,
                "shortDescription", "수정 완료된 게임 소개",
                "platform", "WINDOWS",
                "minimumRequirements", "Windows 10, 8GB RAM"));
        long secondSubmission = submit(build1, metadataRevision);
        approveAll(secondSubmission);
        awaitReady(secondSubmission);

        Response release = Stove.gateway.post(
                "/api/v1/studio/projects/submissions/%d/releases".formatted(secondSubmission),
                null, Journey.asCreator());
        assertThat(release.status()).as("%s", release).isEqualTo(200);
        release1 = release.data().path("releaseId").asLong();
    }

    @Test
    @Order(4)
    @DisplayName("ReleasePublished 이후에만 catalog/store/download가 같은 release를 노출한다")
    void projectsPublishedRelease() {
        Await.untilResponse("catalog release projection",
                () -> Stove.gateway.get("/api/v1/products/by-code/" + PRODUCT_CODE),
                response -> response.status() == 200
                        && response.data().path("releaseId").asLong() == release1);
        Response catalog = Stove.gateway.get("/api/v1/products/by-code/" + PRODUCT_CODE);
        Journey.productId(catalog.data().path("productId").asLong());
        assertThat(catalog.data().path("buildId").asLong()).isEqualTo(build1);

        Await.untilResponse("store release projection",
                () -> Stove.gateway.get("/api/v1/storefront/products?q=" + Journey.STAMP),
                response -> response.itemWhere("productCode", PRODUCT_CODE)
                        .path("releaseId").asLong() == release1);
        Await.untilResponse("download release projection",
                () -> Stove.gateway.get("/api/v1/downloads/%s/manifests".formatted(PRODUCT_CODE)),
                response -> itemByLong(response.data(), "releaseId", release1) != null);
    }

    @Test
    @Order(5)
    @DisplayName("새 빌드 출시 후 이전 검증 빌드로 새 Release를 만들어 rollback한다")
    void publishesPatchAndRollsBack() throws Exception {
        build2 = uploadBuild("1.1.0", "200");
        long patchSubmission = submit(build2, metadataRevision);
        approveAll(patchSubmission);
        awaitReady(patchSubmission);
        Response patch = Stove.gateway.post(
                "/api/v1/studio/projects/submissions/%d/releases".formatted(patchSubmission),
                null, Journey.asCreator());
        assertThat(patch.status()).as("%s", patch).isEqualTo(200);
        long release2 = patch.data().path("releaseId").asLong();
        Await.untilResponse("patch published",
                () -> Stove.gateway.get("/api/v1/products/by-code/" + PRODUCT_CODE),
                response -> response.data().path("releaseId").asLong() == release2);

        Response rollback = Stove.gateway.post(
                "/api/v1/studio/projects/releases/%d/rollback".formatted(release1),
                null, Journey.asCreator());
        assertThat(rollback.status()).as("%s", rollback).isEqualTo(200);
        long rollbackRelease = rollback.data().path("releaseId").asLong();
        assertThat(rollbackRelease).isNotEqualTo(release1).isNotEqualTo(release2);

        Await.untilResponse("rollback projection",
                () -> Stove.gateway.get("/api/v1/products/by-code/" + PRODUCT_CODE),
                response -> response.data().path("releaseId").asLong() == rollbackRelease
                        && response.data().path("buildId").asLong() == build1);
        Await.untilResponse("rollback manifest",
                () -> Stove.gateway.get("/api/v1/downloads/%s/manifests".formatted(PRODUCT_CODE)),
                response -> {
                    JsonNode item = itemByLong(response.data(), "releaseId", rollbackRelease);
                    return item != null && item.path("buildId").asLong() == build1;
                });
    }

    private long uploadBuild(String version, String buildNumber) throws Exception {
        byte[] artifact = artifact(version);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(artifact));
        Map<String, String> ci = Map.of("X-Project-Credential", machineCredential);
        Response session = Stove.gateway.post(
                "/api/v1/studio/ci/projects/%d/upload-sessions".formatted(Journey.gameId()), Map.ofEntries(
                        Map.entry("productVersion", version),
                        Map.entry("buildNumber", buildNumber),
                        Map.entry("platform", "WINDOWS"),
                        Map.entry("architecture", "X86_64"),
                        Map.entry("fileName", "game-" + version + ".zip"),
                        Map.entry("fileSize", artifact.length),
                        Map.entry("sha256", checksum),
                        Map.entry("commitSha", "deadbeef" + buildNumber),
                        Map.entry("repository", "https://github.com/example/game"),
                        Map.entry("ciProvider", "GITHUB"),
                        Map.entry("ciRunId", buildNumber),
                        Map.entry("idempotencyKey", "e2e-" + Journey.STAMP + "-" + buildNumber)), ci);
        assertThat(session.status()).as("%s", session).isEqualTo(200);
        long sessionId = session.data().path("uploadSessionId").asLong();
        long buildId = session.data().path("buildId").asLong();
        String uploadUrl = session.data().path("parts").get(0).path("uploadUrl").asText()
                .replace("http://minio:9000", "http://127.0.0.1:19000");
        Response uploaded = Stove.gateway.putBytes(uploadUrl, artifact);
        assertThat(uploaded.status()).as("%s", uploaded).isIn(200, 201);
        String etag = uploaded.headers().getETag();

        Response completed = Stove.gateway.post(
                "/api/v1/studio/ci/projects/%d/upload-sessions/%d/complete"
                        .formatted(Journey.gameId(), sessionId),
                Map.of("parts", List.of(Map.of("partNumber", 1, "etag", etag))), ci);
        assertThat(completed.status()).as("%s", completed).isEqualTo(200);
        Await.untilResponse("build validation " + buildId,
                () -> Stove.gateway.get(
                        "/api/v1/studio/ci/projects/%d/builds/%d".formatted(Journey.gameId(), buildId), ci),
                response -> "VALIDATED".equals(response.data().path("status").asText()));
        return buildId;
    }

    private long revision(String path, Map<String, ?> body) {
        Response response = Stove.gateway.post(
                "/api/v1/studio/projects/%d/%s".formatted(Journey.gameId(), path),
                body, Journey.asCreator());
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        return response.data().path("revisionId").asLong();
    }

    private long submit(long buildId, long metadataId) {
        Response response = Stove.gateway.post(
                "/api/v1/studio/projects/%d/submissions".formatted(Journey.gameId()), Map.of(
                        "metadataRevisionId", metadataId,
                        "pricingRevisionId", pricingRevision,
                        "ratingRevisionId", ratingRevision,
                        "buildId", buildId), Journey.asCreator());
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        return response.data().path("submissionId").asLong();
    }

    private long reviewCase(long submissionId, String type) {
        final long[] id = {0};
        Await.untilResponse("review cases " + submissionId,
                () -> Stove.gateway.get("/api/v1/reviews/cases?submissionId=" + submissionId,
                        Journey.asReviewer()), response -> {
                    JsonNode item = itemByText(response.data(), "reviewType", type);
                    if (item == null) return false;
                    id[0] = item.path("reviewCaseId").asLong();
                    return id[0] > 0;
                });
        return id[0];
    }

    private void approveAll(long submissionId) {
        for (String type : List.of("RATING", "STORE_PAGE", "BUILD_QA")) {
            long caseId = reviewCase(submissionId, type);
            Map<String, ?> body = "RATING".equals(type) ? Map.of(
                    "ratingCode", "ALL",
                    "certificationNumber", "SELF-" + submissionId,
                    "issuer", "ESD SELF CLASSIFICATION",
                    "issuedAt", Instant.now().toString(),
                    "country", "KR") : Map.of();
            Response approved = Stove.gateway.post(
                    "/api/v1/reviews/cases/%d/approve".formatted(caseId), body, Journey.asReviewer());
            assertThat(approved.status()).as("%s", approved).isEqualTo(200);
        }
    }

    private void awaitReady(long submissionId) {
        Await.untilResponse("submission ready " + submissionId,
                () -> Stove.gateway.get(
                        "/api/v1/studio/projects/submissions/" + submissionId, Journey.asCreator()),
                response -> "READY_FOR_RELEASE".equals(response.data().path("status").asText()));
    }

    private static byte[] artifact(String version) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(("{\"productVersion\":\"" + version
                    + "\",\"entrypoint\":\"game.exe\"}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("game.exe"));
            zip.write("MZ-e2e-executable".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private static JsonNode itemByText(JsonNode values, String field, String expected) {
        for (JsonNode value : values) if (expected.equals(value.path(field).asText())) return value;
        return null;
    }

    private static JsonNode itemByLong(JsonNode values, String field, long expected) {
        for (JsonNode value : values) if (expected == value.path(field).asLong()) return value;
        return null;
    }
}
