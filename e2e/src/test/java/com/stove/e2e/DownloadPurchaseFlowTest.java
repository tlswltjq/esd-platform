package com.stove.e2e;

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
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("download")
@ExtendWith(LocalStackExtension.class)
@LocalStackApps({StackApp.AUTH, StackApp.STUDIO, StackApp.WEB_BFF, StackApp.GATEWAY, StackApp.REVIEW, StackApp.CATALOG,
        StackApp.STORE, StackApp.ORDER, StackApp.PAYMENT, StackApp.LICENSE, StackApp.DOWNLOAD})
class DownloadPurchaseFlowTest {
    /** 앞 걸음이 만든 상품·주문·티켓. 예전에는 실행기가 걸음마다 테스트를 따로 불러 파일로 넘겼다. */
    private static String journey;
    static ObjectNode saved() throws Exception { return (ObjectNode) JSON.readTree(journey); }
    static void save(ObjectNode state) throws Exception { journey = JSON.writeValueAsString(state); }
    static Account buyer(ObjectNode state) throws Exception { return login(state.path("buyerEmail").asText(), "creator-password-1234"); }
    static Account creator(ObjectNode state) throws Exception { return login(state.path("creatorEmail").asText(), "creator-password-1234"); }
    static String code(ObjectNode state) { return state.path("product").path("productCode").asText(); }
    static String ticketPath(ObjectNode state) { return "/api/v1/downloads/" + code(state) + "/ticket"; }
    static JsonNode order(ObjectNode state, Account buyer) {
        JsonNode product = state.path("product");
        E2eClient.Response result = Stove.gateway.post("/api/v1/orders", Map.of("requestId", UUID.randomUUID().toString(),
                "productCode", code(state), "expectedContentVersion", product.path("contentVersion").asLong(),
                "expectedAmount", product.path("price").asLong()), buyer.headers());
        assertThat(result.status()).isEqualTo(200); return result.data();
    }
    static void waitPaid(String number, Account account, String expected) {
        Awaitility.await().atMost(Duration.ofSeconds(50)).untilAsserted(() -> {
            E2eClient.Response result = Stove.gateway.get("/api/v1/orders/" + number, account.headers());
            assertThat(result.status()).isEqualTo(200); assertThat(result.data().path("status").asText()).isEqualTo(expected);
        });
    }
    static void approveAndPublish(long projectId, Account creator) throws Exception {
        Account reviewer = login(REVIEWER_EMAIL, REVIEWER_PASSWORD);
        long review = request(projectId, creator, reviewer);
        assertThat(Stove.gateway.post(reviewPath(review) + "/approve", null, reviewer.headers()).status()).isEqualTo(200);
        waitForResult(projectId, review, creator, "APPROVED");
        assertThat(Stove.gateway.post(path(projectId) + "/release-requests", ReviewJourney.submission(path(projectId), creator.headers()), creator.headers()).status()).isEqualTo(202);
        Awaitility.await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertThat(data(projectId, creator).path("currentReleaseRequest").path("status").asText()).isEqualTo("COMPLETED"));
    }
    static JsonNode ticket(ObjectNode state, Account buyer) {
        E2eClient.Response result = Stove.gateway.get(ticketPath(state), buyer.headers());
        assertThat(result.status()).isEqualTo(200); assertThat(result.headers().getCacheControl()).contains("no-store");
        assertThat(result.data().size()).isEqualTo(6); return result.data();
    }
    static byte[] rawDownload(String url, int expected) throws Exception {
        HttpResponse<byte[]> result = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(result.statusCode()).isEqualTo(expected); return result.body();
    }
    static void verifyZip(JsonNode ticket, byte[] original) throws Exception {
        byte[] downloaded = rawDownload(ticket.path("downloadUrl").asText(), 200);
        assertThat(downloaded).isEqualTo(original); assertThat(ticket.path("fileSize").asLong()).isEqualTo(original.length);
        assertThat(ticket.path("checksum").asText()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
    }
    static void stableLibrary(ObjectNode state, Account buyer) {
        assertThat(Stove.gateway.get("/api/v1/library/" + code(state), buyer.headers()).data()).isEqualTo(state.path("license"));
    }
    private static void preparePendingPurchase() throws Exception {
        Account creator = account(true); Account buyer = account(false); Project project = project(creator);
        approveAndPublish(project.id(), creator);
        String code = data(project.id(), creator).path("productCode").asText();
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(Stove.gateway.get("/api/v1/storefront/products/" + code).status()).isEqualTo(200));
        ObjectNode state = JSON.createObjectNode().put("creatorEmail", creator.email()).put("buyerEmail", buyer.email())
                .put("projectId", project.id()).put("zip", Base64.getEncoder().encodeToString(project.zip()));
        state.set("product", Stove.gateway.get("/api/v1/products/by-code/" + code).data());
        JsonNode order = order(state, buyer); state.put("orderNo", order.path("orderNo").asText());
        waitPaid(state.path("orderNo").asText(), buyer, "PENDING");
        assertThat(Stove.gateway.get(ticketPath(state), buyer.headers()).status()).isEqualTo(403); save(state);
    }
    private static void paidOrderDownloadsOriginalZip() throws Exception {
        ObjectNode state = saved(); Account buyer = buyer(state); waitPaid(state.path("orderNo").asText(), buyer, "PAID");
        state.set("license", ReviewJourney.registeredLibrary(buyer, code(state)));
        JsonNode result = ticket(state, buyer); verifyZip(result, Base64.getDecoder().decode(state.path("zip").asText()));
        assertThat(result.path("version").asText()).isEqualTo("1"); save(state);
    }
    private static void failedAndOtherUsersCannotDownloadOrSelectAnotherOwner() throws Exception {
        ObjectNode state = saved(); Account failed = account(false); JsonNode order = order(state, failed);
        waitPaid(order.path("orderNo").asText(), failed, "FAILED");
        assertThat(Stove.gateway.get(ticketPath(state), failed.headers()).status()).isEqualTo(403);
        Account outsider = account(false); Map<String, String> forged = new HashMap<>(outsider.headers()); forged.put("X-Member-Id", buyer(state).subject());
        assertThat(Stove.gateway.get(ticketPath(state), forged).status()).isEqualTo(403);
        assertThat(Stove.gateway.get(ticketPath(state) + "?memberId=" + buyer(state).subject(), forged).status()).isEqualTo(400);
        assertThat(Stove.gateway.get(ticketPath(state)).status()).isEqualTo(401);
        assertThat(Stove.gateway.get(ticketPath(state), login(REVIEWER_EMAIL, REVIEWER_PASSWORD).headers()).status()).isEqualTo(403);
        assertThat(Stove.gateway.get("/internal/v1/products/" + code(state) + "/build", buyer(state).headers()).status()).isNotEqualTo(200);
        assertThat(Stove.catalog.get("/internal/v1/products/" + code(state) + "/build", buyer(state).headers()).status()).isEqualTo(401);
        assertThat(Stove.gateway.get("/v3/api-docs/catalog").body().toString()).doesNotContain("/internal/");
        assertThat(Stove.gateway.get("/v3/api-docs/download").body().path("paths").size()).isEqualTo(1);
        stableLibrary(state, buyer(state));
    }
    private static void unpublishedEditsStillDownloadPreviousPublishedBuild() throws Exception {
        ObjectNode state = saved(); Account creator = creator(state); long project = state.path("projectId").asLong();
        byte[] next = zip("new full build for current publication"); upload(project, creator, "2", next); page(project, creator, "Updated title", 1500);
        state.put("nextZip", Base64.getEncoder().encodeToString(next));
        JsonNode result = ticket(state, buyer(state)); assertThat(result.path("version").asText()).isEqualTo("1");
        verifyZip(result, Base64.getDecoder().decode(state.path("zip").asText())); save(state);
    }
    private static void newlyPublishedBuildUsesSamePermanentRight() throws Exception {
        ObjectNode state = saved(); approveAndPublish(state.path("projectId").asLong(), creator(state));
        JsonNode result = ticket(state, buyer(state)); assertThat(result.path("version").asText()).isEqualTo("2");
        verifyZip(result, Base64.getDecoder().decode(state.path("nextZip").asText())); stableLibrary(state, buyer(state));
    }
    private static void expiresAndReissuesTheActualStorageUrl() throws Exception {
        ObjectNode state = saved(); Account buyer = buyer(state); JsonNode original = ticket(state, buyer);
        Instant expires = Instant.parse(original.path("expiresAt").asText());
        Thread.sleep(Math.max(1, Duration.between(Instant.now(), expires.plusSeconds(1)).toMillis()));
        rawDownload(original.path("downloadUrl").asText(), 403);
        JsonNode fresh = ticket(state, buyer); verifyZip(fresh, Base64.getDecoder().decode(state.path("nextZip").asText()));
        stableLibrary(state, buyer);
    }
    private static void prepareFreshUrlBeforeOutage() throws Exception {
        ObjectNode state = saved(); state.set("activeTicket", ticket(state, buyer(state))); save(state);
    }
    private static void licenseOutageRejectsNewIssuanceButExistingUrlWorks() throws Exception {
        ObjectNode state = saved(); assertThat(Stove.gateway.get(ticketPath(state), buyer(state).headers()).status()).isEqualTo(503);
        verifyZip(state.path("activeTicket"), Base64.getDecoder().decode(state.path("nextZip").asText()));
    }
    private static void catalogOutagePreservesExistingFileAndLibrary() throws Exception {
        ObjectNode state = saved(); assertThat(Stove.gateway.get(ticketPath(state), buyer(state).headers()).status()).isEqualTo(503);
        verifyZip(state.path("activeTicket"), Base64.getDecoder().decode(state.path("nextZip").asText())); stableLibrary(state, buyer(state));
    }
    private static void storageOutageRejectsIssuance() throws Exception {
        ObjectNode state = saved(); assertThat(Stove.gateway.get(ticketPath(state), buyer(state).headers()).status()).isEqualTo(503); stableLibrary(state, buyer(state));
    }
    private static void missingOrChangedFileRejectsIssuance() throws Exception {
        ObjectNode state = saved(); assertThat(Stove.gateway.get(ticketPath(state), buyer(state).headers()).status()).isEqualTo(409); stableLibrary(state, buyer(state));
    }
    private static void restoredFileAndStatelessRestartRecoverDownloads() throws Exception {
        ObjectNode state = saved(); JsonNode result = ticket(state, buyer(state));
        verifyZip(result, Base64.getDecoder().decode(state.path("nextZip").asText())); stableLibrary(state, buyer(state));
    }

    /**
     * 실제 ZIP과 체크섬, 공개본 교체, URL 만료, 소유 거절, License·Catalog·저장소 정지, 사라지거나 바뀐 파일, 상태 없는
     * 재시작을 한 흐름으로 본다. 테스트가 앱과 저장소를 직접 멈추고 되살린다(결정 e2e-stack-in-junit).
     */
    @Test
    void downloadsFollowRightsAndPublicationsAndFailClosedThroughOutages(LocalStack stack) throws Exception {
        // 처리를 끈 Payment: 결제 전에는 받을 수 없다.
        stack.restart(StackApp.PAYMENT, "--stove.payment.processing-enabled=false");
        preparePendingPurchase();
        stack.restart(StackApp.PAYMENT);
        paidOrderDownloadsOriginalZip();
        PaymentFault.restart(stack, PaymentFault.DECLINE);
        failedAndOtherUsersCannotDownloadOrSelectAnotherOwner();
        unpublishedEditsStillDownloadPreviousPublishedBuild();
        newlyPublishedBuildUsesSamePermanentRight();

        // 2초짜리 URL: 만료된 URL은 저장소가 거절하고 새로 받으면 된다.
        stack.restart(StackApp.DOWNLOAD, "--stove.download-storage.presign-ttl=PT2S");
        expiresAndReissuesTheActualStorageUrl();
        stack.restart(StackApp.DOWNLOAD);

        prepareFreshUrlBeforeOutage();
        stack.stop(StackApp.LICENSE);
        licenseOutageRejectsNewIssuanceButExistingUrlWorks();
        stack.start(StackApp.LICENSE);
        prepareFreshUrlBeforeOutage();
        stack.stop(StackApp.CATALOG);
        catalogOutagePreservesExistingFileAndLibrary();
        stack.start(StackApp.CATALOG);
        stack.stopStorage();
        try {
            storageOutageRejectsIssuance();
        } finally {
            stack.startStorage();
        }

        // 공개 빌드 파일을 지우고, 크기가 다른 것으로 바꾸고, 되돌린다. 위치는 Catalog가 Download에만 알려 주는 값이다.
        ObjectNode state = saved();
        E2eClient.Response build = Stove.catalog.get("/internal/v1/products/" + code(state) + "/build",
                Map.of("X-Download-Service-Key", stack.serviceKey("CATALOG_DOWNLOAD_SERVICE_KEY")));
        assertThat(build.status()).as(build.toString()).isEqualTo(200);
        String storagePath = build.data().path("storagePath").asText();
        assertThat(storagePath).startsWith("s3://");
        String object = "admin/" + storagePath.substring("s3://".length());
        stack.storage("cp", object, "/tmp/download-original.zip");
        stack.storage("rm", object);
        missingOrChangedFileRejectsIssuance();
        stack.storageShell("printf changed-size | mc pipe " + object);
        missingOrChangedFileRejectsIssuance();
        stack.storage("cp", "/tmp/download-original.zip", object);
        restoredFileAndStatelessRestartRecoverDownloads();
        stack.restart(StackApp.DOWNLOAD);
        restoredFileAndStatelessRestartRecoverDownloads();

        assertThat(stack.sql("SELECT COUNT(*) FROM stove_order.purchase_order WHERE status='PAID'")).isEqualTo("1");
        assertThat(stack.sql("SELECT COUNT(*) FROM stove_order.purchase_order WHERE status='FAILED'")).isEqualTo("1");
        assertThat(stack.sql("SELECT COUNT(*) FROM stove_license.product_license")).isEqualTo("1");
        assertThat(stack.sql("SELECT COUNT(*) FROM stove_license.processed_event WHERE consumer_group='license-paid-order'"))
                .isEqualTo("1");
        assertThat(stack.sql("SELECT COUNT(*) FROM stove_license.license_purchase")).isEqualTo("0");
        assertThat(stack.sql("SELECT COUNT(*) FROM stove_catalog.catalog_product")).isEqualTo("1");
        assertThat(stack.sql("SELECT COUNT(*) FROM stove_catalog.catalog_release WHERE status='COMPLETED'")).isEqualTo("2");
        assertThat(stack.sql("SELECT COUNT(*) FROM stove_payment.simulated_payment_charge")).isEqualTo("0");
        assertThat(stack.sql("SELECT COUNT(*) FROM stove_payment.purchase_payment")).isEqualTo("2");
    }
}
