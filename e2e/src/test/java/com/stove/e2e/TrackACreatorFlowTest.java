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

/** P0·P1 전체 경로: OIDC → CI 업로드 → 심사 → 채널별 출시 → rollback. */
@Order(1)
@DisplayName("트랙 A — 셀프 퍼블리싱 P0·P1")
class TrackACreatorFlowTest {

    private static final String CREATOR_EMAIL = "creator-" + Journey.STAMP + "@e2e.local";
    private static final String CREATOR_PASSWORD = "creator-password-" + Journey.STAMP;
    private static String creatorSubject;
    private static String machineCredential;
    private static long build1;
    private static long build2;
    private static long metadataRevision;
    private static long pricingRevision;
    private static long ratingRevision;
    private static long release1;
    private static long p1MetadataRevision;
    private static long p1Submission;

    @Test
    @Order(1)
    @DisplayName("OIDC 가입·PKCE 로그인 후 개인 Workspace의 프로젝트를 만든다")
    void authenticatesAndCreatesProject() {
        Response signup = Stove.auth.post("/api/v1/auth/signup", Map.of(
                "email", CREATOR_EMAIL, "password", CREATOR_PASSWORD));
        assertThat(signup.status()).as("%s", signup).isEqualTo(200);
        creatorSubject = signup.data().path("subject").asText();
        assertThat(creatorSubject).isNotBlank();

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
    @DisplayName("ClamAV가 악성코드 테스트 패턴을 탐지하면 빌드를 거부한다")
    void rejectsMalwareBuild() throws Exception {
        byte[] artifact = malwareArtifact("0.0.1-malware-test");
        long buildId = uploadArtifact("0.0.1-malware-test", "malware", artifact);
        Map<String, String> ci = Map.of("X-Project-Credential", machineCredential);

        Await.untilResponse("malware rejection " + buildId,
                () -> Stove.gateway.get(
                        "/api/v1/studio/ci/projects/%d/builds/%d".formatted(Journey.gameId(), buildId), ci),
                response -> "FAILED".equals(response.data().path("status").asText())
                        && "MALWARE_DETECTED".equals(response.data().path("failureCode").asText()));
    }

    @Test
    @Order(4)
    @DisplayName("불변 revision을 제출하고 변경 요청 뒤 새 revision으로 재제출한다")
    void requestsChangesAndResubmits() {
        metadataRevision = revision("store-page-revisions", Map.of(
                "title", Journey.PRODUCT_TITLE,
                "shortDescription", "첫 심사용 소개",
                "platform", "WINDOWS",
                "minimumRequirements", "Windows 10"));
        pricingRevision = revision("pricing-revisions", Map.of("price", PRICE));
        ratingRevision = ratingRevision(Map.of(
                "violence", "NONE",
                "sexualContent", "NONE",
                "language", "NONE",
                "drugUse", false,
                "cashGambling", false), "SELF_CLASSIFICATION", "ALL");

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
    @Order(5)
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
    @Order(6)
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

    @Test
    @Order(7)
    @DisplayName("18세 GRAC 경로는 외부 접수 증빙 전 승인·출시를 막는다")
    void requiresGracExternalSubmission() {
        long gracRatingRevision = ratingRevision(Map.of(
                "violence", "STRONG",
                "sexualContent", "NONE",
                "language", "NONE",
                "drugUse", false,
                "cashGambling", false), "GRAC", "18");
        long submissionId = submit(build1, metadataRevision, gracRatingRevision);

        for (String type : List.of("STORE_PAGE", "BUILD_QA", "LEGAL", "SDK_COMPLIANCE", "COMMERCIAL")) {
            Response approved = Stove.gateway.post(
                    "/api/v1/reviews/cases/%d/approve".formatted(reviewCase(submissionId, type)),
                    Map.of(), Journey.asReviewer());
            assertThat(approved.status()).as("%s", approved).isEqualTo(200);
        }

        long ratingCase = reviewCase(submissionId, "RATING");
        Map<String, ?> ratingEvidence = Map.of(
                "ratingCode", "18",
                "certificationNumber", "GRAC-CERT-" + submissionId,
                "issuer", "GRAC",
                "issuedAt", Instant.now().toString(),
                "country", "KR");
        Response prematureApproval = Stove.gateway.post(
                "/api/v1/reviews/cases/%d/approve".formatted(ratingCase),
                ratingEvidence, Journey.asReviewer());
        assertThat(prematureApproval.status()).as("%s", prematureApproval).isEqualTo(409);

        Response prematureRelease = Stove.gateway.post(
                "/api/v1/studio/projects/submissions/%d/releases".formatted(submissionId),
                null, Journey.asCreator());
        assertThat(prematureRelease.status()).as("%s", prematureRelease).isEqualTo(409);

        Response externalSubmission = Stove.gateway.post(
                "/api/v1/reviews/cases/%d/external-submission".formatted(ratingCase), Map.of(
                        "applicationNumber", "GRAC-APPLICATION-" + submissionId,
                        "submittedAt", Instant.now().toString(),
                        "evidenceUrl", "https://evidence.example/grac/" + submissionId),
                Journey.asReviewer());
        assertThat(externalSubmission.status()).as("%s", externalSubmission).isEqualTo(200);

        Response approved = Stove.gateway.post(
                "/api/v1/reviews/cases/%d/approve".formatted(ratingCase),
                ratingEvidence, Journey.asReviewer());
        assertThat(approved.status()).as("%s", approved).isEqualTo(200);
        awaitReady(submissionId);

        Response release = Stove.gateway.post(
                "/api/v1/studio/projects/submissions/%d/releases".formatted(submissionId),
                null, Journey.asCreator());
        assertThat(release.status()).as("%s", release).isEqualTo(200);
    }

    @Test
    @Order(8)
    @DisplayName("상점 상세 초안을 미리보고·수정·발행하고 발행 전 제출은 거절한다")
    void managesRichStorePageDraft() {
        Response created = Stove.gateway.post(
                "/api/v1/studio/projects/%d/store-page-revisions".formatted(Journey.gameId()),
                richStorePage("초안 상세 소개", true), Journey.asCreator());
        assertThat(created.status()).as("%s", created).isEqualTo(200);
        assertThat(created.data().path("storePageStatus").asText()).isEqualTo("DRAFT");
        p1MetadataRevision = created.data().path("revisionId").asLong();

        Response prematureSubmission = Stove.gateway.post(
                "/api/v1/studio/projects/%d/submissions".formatted(Journey.gameId()), Map.of(
                        "metadataRevisionId", p1MetadataRevision,
                        "pricingRevisionId", pricingRevision,
                        "ratingRevisionId", ratingRevision,
                        "buildId", build1), Journey.asCreator());
        assertThat(prematureSubmission.status()).as("%s", prematureSubmission).isEqualTo(409);

        Response preview = Stove.gateway.get(
                "/api/v1/studio/projects/%d/store-page-revisions/%d/preview"
                        .formatted(Journey.gameId(), p1MetadataRevision), Journey.asCreator());
        assertThat(preview.status()).as("%s", preview).isEqualTo(200);
        assertThat(preview.data().path("status").asText()).isEqualTo("DRAFT");
        assertThat(preview.data().path("localizations").path("en-US").path("title").asText())
                .isEqualTo(Journey.PRODUCT_TITLE + " EN");
        assertThat(preview.data().path("prices").path("KRW").asLong()).isEqualTo(PRICE);

        Response updated = Stove.gateway.put(
                "/api/v1/studio/projects/%d/store-page-revisions/%d"
                        .formatted(Journey.gameId(), p1MetadataRevision),
                richStorePage("수정한 출시용 상세 소개", true), Journey.asCreator());
        assertThat(updated.status()).as("%s", updated).isEqualTo(200);
        assertThat(updated.data().path("revisionId").asLong()).isEqualTo(p1MetadataRevision);

        Response published = Stove.gateway.post(
                "/api/v1/studio/projects/%d/store-page-revisions/%d/publish"
                        .formatted(Journey.gameId(), p1MetadataRevision), null, Journey.asCreator());
        assertThat(published.status()).as("%s", published).isEqualTo(200);
        assertThat(published.data().path("storePageStatus").asText()).isEqualTo("PUBLISHED");

        Response immutable = Stove.gateway.put(
                "/api/v1/studio/projects/%d/store-page-revisions/%d"
                        .formatted(Journey.gameId(), p1MetadataRevision),
                richStorePage("발행 후 변조", true), Journey.asCreator());
        assertThat(immutable.status()).as("%s", immutable).isEqualTo(409);
    }

    @Test
    @Order(9)
    @DisplayName("심사 배정·체크리스트·차단·이의제기·이력과 검색을 하나의 안건에서 검증한다")
    void operatesAndAppealsReview() {
        p1Submission = submit(build1, p1MetadataRevision);
        JsonNode review = reviewCaseNode(p1Submission, "STORE_PAGE");
        long caseId = review.path("reviewCaseId").asLong();

        Response assigned = Stove.gateway.post(
                "/api/v1/reviews/cases/%d/assignment".formatted(caseId), Map.of(
                        "assignee", "reviewer:p1-store",
                        "expectedVersion", review.path("entityVersion").asLong()), Journey.asReviewer());
        assertThat(assigned.status()).as("%s", assigned).isEqualTo(200);
        assertThat(assigned.data().path("assignedTo").asText()).isEqualTo("reviewer:p1-store");

        Response checklist = Stove.gateway.post(
                "/api/v1/reviews/cases/%d/checklist".formatted(caseId), Map.of(
                        "checklist", Map.of("copy", true, "assets", true, "localization", true),
                        "internalMemo", "P1 운영 메모",
                        "expectedVersion", assigned.data().path("entityVersion").asLong()), Journey.asReviewer());
        assertThat(checklist.status()).as("%s", checklist).isEqualTo(200);
        assertThat(checklist.data().path("internalMemo").asText()).isEqualTo("P1 운영 메모");

        Response blocked = Stove.gateway.post(
                "/api/v1/reviews/cases/%d/block".formatted(caseId), Map.of(
                        "reasonCode", "ASSET_QUALITY",
                        "internalMemo", "원본 자산 확인 필요",
                        "evidenceUrl", "https://evidence.example/store/" + caseId,
                        "expectedVersion", checklist.data().path("entityVersion").asLong()), Journey.asReviewer());
        assertThat(blocked.status()).as("%s", blocked).isEqualTo(200);
        assertThat(blocked.data().path("status").asText()).isEqualTo("BLOCKED");

        Response appealed = Stove.gateway.post(
                "/api/v1/reviews/cases/%d/appeal".formatted(caseId), Map.of(
                        "reason", "원본 자산을 교체했습니다.",
                        "expectedVersion", blocked.data().path("entityVersion").asLong()), Journey.asReviewer());
        assertThat(appealed.status()).as("%s", appealed).isEqualTo(200);
        assertThat(appealed.data().path("status").asText()).isEqualTo("REQUESTED");
        assertThat(appealed.data().path("reviewRound").asInt()).isEqualTo(2);

        Response history = Stove.gateway.get(
                "/api/v1/reviews/cases/%d/history".formatted(caseId), Journey.asReviewer());
        assertThat(history.status()).as("%s", history).isEqualTo(200);
        assertThat(itemByText(history.data(), "action", "ASSIGNED")).isNotNull();
        assertThat(itemByText(history.data(), "action", "CHECKLIST_UPDATED")).isNotNull();
        assertThat(itemByText(history.data(), "action", "BLOCKED")).isNotNull();
        assertThat(itemByText(history.data(), "action", "APPEALED")).isNotNull();

        Response search = Stove.gateway.get(
                "/api/v1/reviews/cases/search?submissionId=%d&reviewType=STORE_PAGE&assignee=reviewer:p1-store&page=0&size=5"
                        .formatted(p1Submission), Journey.asReviewer());
        assertThat(search.status()).as("%s", search).isEqualTo(200);
        assertThat(search.data().path("totalElements").asLong()).isEqualTo(1);
        assertThat(search.data().path("items").get(0).path("reviewCaseId").asLong()).isEqualTo(caseId);

        approveAll(p1Submission);
        awaitReady(p1Submission);
    }

    @Test
    @Order(10)
    @DisplayName("DEV→TEST→STAGE→LIVE 승격, 테스터 설치, 예약 변경·취소를 검증한다")
    void promotesChannelsAndManagesSchedule() {
        Response dev = Stove.gateway.post(
                "/api/v1/studio/projects/submissions/%d/releases".formatted(p1Submission),
                Map.of("channel", "DEV", "timeZone", "Asia/Seoul"), Journey.asCreator());
        assertPublishedRelease(dev, "DEV");

        Response tester = Stove.gateway.post(
                "/api/v1/studio/projects/%d/testers".formatted(Journey.gameId()), Map.of(
                        "testerSubject", creatorSubject,
                        "channel", "DEV"), Journey.asCreator());
        assertThat(tester.status()).as("%s", tester).isEqualTo(200);
        long grantId = tester.data().path("grantId").asLong();

        Response installation = Stove.gateway.get(
                "/api/v1/studio/tester/builds/%d/installation?channel=DEV".formatted(build1),
                Journey.asCreator());
        assertThat(installation.status()).as("%s", installation).isEqualTo(200);
        assertThat(installation.data().path("downloadUrl").asText()).startsWith("http");

        Response test = promote(dev.data().path("releaseId").asLong(), "TEST");
        Response stage = promote(test.data().path("releaseId").asLong(), "STAGE");
        Response live = promote(stage.data().path("releaseId").asLong(), "LIVE");
        assertThat(live.data().path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(live.data().path("changeType").asText()).isEqualTo("MATERIAL_CHANGE");

        Response revoked = Stove.gateway.post(
                "/api/v1/studio/projects/%d/testers/%d/revoke".formatted(Journey.gameId(), grantId),
                null, Journey.asCreator());
        assertThat(revoked.status()).as("%s", revoked).isEqualTo(200);
        Response grants = Stove.gateway.get(
                "/api/v1/studio/projects/%d/testers".formatted(Journey.gameId()), Journey.asCreator());
        assertThat(grants.status()).as("%s", grants).isEqualTo(200);
        assertThat(itemByLong(grants.data(), "grantId", grantId).path("active").asBoolean()).isFalse();

        long changedPricingRevision = revision("pricing-revisions", Map.of("price", PRICE + 1_000));
        long scheduledSubmission = submit(build1, p1MetadataRevision, changedPricingRevision, ratingRevision);
        approveAll(scheduledSubmission);
        awaitReady(scheduledSubmission);
        Instant initialPublishAt = Instant.now().plusSeconds(3_600);
        Response scheduled = Stove.gateway.post(
                "/api/v1/studio/projects/submissions/%d/releases".formatted(scheduledSubmission), Map.of(
                        "channel", "DEV",
                        "publishAt", initialPublishAt.toString(),
                        "timeZone", "Asia/Seoul"), Journey.asCreator());
        assertThat(scheduled.status()).as("%s", scheduled).isEqualTo(200);
        assertThat(scheduled.data().path("status").asText()).isEqualTo("SCHEDULED");
        assertThat(scheduled.data().path("changeType").asText()).isEqualTo("MATERIAL_CHANGE");
        long scheduledRelease = scheduled.data().path("releaseId").asLong();

        Instant rescheduledAt = Instant.now().plusSeconds(7_200);
        Response rescheduled = Stove.gateway.post(
                "/api/v1/studio/projects/releases/%d/reschedule".formatted(scheduledRelease), Map.of(
                        "publishAt", rescheduledAt.toString(),
                        "timeZone", "UTC"), Journey.asCreator());
        assertThat(rescheduled.status()).as("%s", rescheduled).isEqualTo(200);
        assertThat(rescheduled.data().path("publishTimeZone").asText()).isEqualTo("UTC");

        Response cancelled = Stove.gateway.post(
                "/api/v1/studio/projects/releases/%d/cancel".formatted(scheduledRelease),
                null, Journey.asCreator());
        assertThat(cancelled.status()).as("%s", cancelled).isEqualTo(200);
    }

    private long uploadBuild(String version, String buildNumber) throws Exception {
        byte[] artifact = artifact(version);
        long buildId = uploadArtifact(version, buildNumber, artifact);
        Map<String, String> ci = Map.of("X-Project-Credential", machineCredential);
        Await.untilResponse("build validation " + buildId,
                () -> Stove.gateway.get(
                        "/api/v1/studio/ci/projects/%d/builds/%d".formatted(Journey.gameId(), buildId), ci),
                response -> "VALIDATED".equals(response.data().path("status").asText()));
        return buildId;
    }

    private long uploadArtifact(String version, String buildNumber, byte[] artifact) throws Exception {
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
                        Map.entry("sourceRef", "refs/heads/main"),
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
        return buildId;
    }

    private long revision(String path, Map<String, ?> body) {
        Response response = Stove.gateway.post(
                "/api/v1/studio/projects/%d/%s".formatted(Journey.gameId(), path),
                body, Journey.asCreator());
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        return response.data().path("revisionId").asLong();
    }

    private long ratingRevision(Map<String, ?> questionnaire, String expectedPath,
                                String expectedRatingCode) {
        Response response = Stove.gateway.post(
                "/api/v1/studio/projects/%d/rating-revisions".formatted(Journey.gameId()),
                Map.of("questionnaire", questionnaire), Journey.asCreator());
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        assertThat(response.data().path("country").asText()).isEqualTo("KR");
        assertThat(response.data().path("policyVersion").asText()).isEqualTo("KR-2026-01");
        assertThat(response.data().path("resolvedPath").asText()).isEqualTo(expectedPath);
        assertThat(response.data().path("recommendedRatingCode").asText()).isEqualTo(expectedRatingCode);
        return response.data().path("revisionId").asLong();
    }

    private long submit(long buildId, long metadataId) {
        return submit(buildId, metadataId, ratingRevision);
    }

    private long submit(long buildId, long metadataId, long selectedRatingRevision) {
        return submit(buildId, metadataId, pricingRevision, selectedRatingRevision);
    }

    private long submit(long buildId, long metadataId, long selectedPricingRevision,
                        long selectedRatingRevision) {
        Response response = Stove.gateway.post(
                "/api/v1/studio/projects/%d/submissions".formatted(Journey.gameId()), Map.of(
                        "metadataRevisionId", metadataId,
                        "pricingRevisionId", selectedPricingRevision,
                        "ratingRevisionId", selectedRatingRevision,
                        "buildId", buildId), Journey.asCreator());
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        return response.data().path("submissionId").asLong();
    }

    private long reviewCase(long submissionId, String type) {
        return reviewCaseNode(submissionId, type).path("reviewCaseId").asLong();
    }

    private JsonNode reviewCaseNode(long submissionId, String type) {
        final JsonNode[] found = {null};
        final long[] id = {0};
        Await.untilResponse("review cases " + submissionId,
                () -> Stove.gateway.get("/api/v1/reviews/cases?submissionId=" + submissionId,
                        Journey.asReviewer()), response -> {
                    JsonNode item = itemByText(response.data(), "reviewType", type);
                    if (item == null) return false;
                    id[0] = item.path("reviewCaseId").asLong();
                    found[0] = item;
                    return id[0] > 0;
                });
        return found[0];
    }

    private Response promote(long sourceReleaseId, String targetChannel) {
        Response response = Stove.gateway.post(
                "/api/v1/studio/projects/releases/%d/promote".formatted(sourceReleaseId),
                Map.of("targetChannel", targetChannel, "timeZone", "Asia/Seoul"), Journey.asCreator());
        assertPublishedRelease(response, targetChannel);
        return response;
    }

    private void assertPublishedRelease(Response response, String channel) {
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        assertThat(response.data().path("channel").asText()).isEqualTo(channel);
        assertThat(response.data().path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(response.data().path("smokeTestStatus").asText()).isEqualTo("PASSED");
    }

    private Map<String, Object> richStorePage(String detailedDescription, boolean draft) {
        return Map.ofEntries(
                Map.entry("title", Journey.PRODUCT_TITLE),
                Map.entry("shortDescription", "P1 기능을 모두 갖춘 게임 소개"),
                Map.entry("detailedDescription", detailedDescription),
                Map.entry("localizations", Map.of("en-US", Map.of(
                        "title", Journey.PRODUCT_TITLE + " EN",
                        "shortDescription", "A complete P1 store page",
                        "detailedDescription", "Localized launch description"))),
                Map.entry("genres", List.of("ACTION", "INDIE")),
                Map.entry("tags", List.of("CO_OP", "CONTROLLER")),
                Map.entry("developer", "ESD Studio"),
                Map.entry("publisher", "ESD Publishing"),
                Map.entry("screenshots", List.of("https://cdn.example/screenshots/1.png")),
                Map.entry("trailers", List.of("https://cdn.example/trailers/1.mp4")),
                Map.entry("iconUrl", "https://cdn.example/icons/game.png"),
                Map.entry("coverUrl", "https://cdn.example/covers/game.png"),
                Map.entry("supportedLanguages", List.of("ko-KR", "en-US")),
                Map.entry("platform", "WINDOWS"),
                Map.entry("minimumRequirements", "Windows 10, 8GB RAM"),
                Map.entry("recommendedRequirements", "Windows 11, 16GB RAM"),
                Map.entry("features", List.of("ACHIEVEMENTS", "CLOUD_SAVE")),
                Map.entry("supportUrl", "https://support.example/game"),
                Map.entry("privacyPolicyUrl", "https://legal.example/privacy"),
                Map.entry("eulaUrl", "https://legal.example/eula"),
                Map.entry("salesCountries", List.of("KR", "US")),
                Map.entry("prices", Map.of("KRW", PRICE, "USD", 1499)),
                Map.entry("draft", draft));
    }

    private void approveAll(long submissionId) {
        for (String type : List.of("RATING", "STORE_PAGE", "BUILD_QA", "LEGAL", "SDK_COMPLIANCE", "COMMERCIAL")) {
            long caseId = reviewCase(submissionId, type);
            Map<String, ?> body = "RATING".equals(type) ? Map.of(
                    "ratingCode", "ALL") : Map.of();
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

    private static byte[] malwareArtifact(String version) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(("{\"productVersion\":\"" + version
                    + "\",\"entrypoint\":\"game.exe\"}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("game.exe"));
            zip.write("MZ-e2e-executable".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("eicar.com"));
            // 표준 EICAR 문자열은 실행 가능한 악성코드가 아니라 백신 연결을 검증하는 테스트 패턴이다.
            // 소스 파일 자체가 로컬 백신에 잡히지 않도록 런타임에 두 조각을 합친다.
            String eicar = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$"
                    + "EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
            zip.write(eicar.getBytes(StandardCharsets.US_ASCII));
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
