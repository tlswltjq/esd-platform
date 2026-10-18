package com.stove.e2e;

import static com.stove.e2e.ReviewJourney.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;

@Tag("download")
class DownloadPurchaseFlowTest {
    static Path evidence() { return Path.of(System.getProperty("stove.e2e.download-evidence")); }
    static ObjectNode saved() throws Exception { return (ObjectNode) JSON.readTree(Files.readString(evidence())); }
    static void save(ObjectNode state) throws Exception {
        Files.writeString(evidence(), JSON.writeValueAsString(state));
        Files.setPosixFilePermissions(evidence(), PosixFilePermissions.fromString("rw-------"));
    }
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
        assertThat(Stove.gateway.post(path(projectId) + "/release-requests", null, creator.headers()).status()).isEqualTo(202);
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
    @Test void preparePendingPurchase() throws Exception {
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
    @Test void paidOrderDownloadsOriginalZip() throws Exception {
        ObjectNode state = saved(); Account buyer = buyer(state); waitPaid(state.path("orderNo").asText(), buyer, "PAID");
        Awaitility.await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> assertThat(Stove.gateway.get("/api/v1/library/" + code(state), buyer.headers()).status()).isEqualTo(200));
        state.set("license", Stove.gateway.get("/api/v1/library/" + code(state), buyer.headers()).data());
        JsonNode result = ticket(state, buyer); verifyZip(result, Base64.getDecoder().decode(state.path("zip").asText()));
        assertThat(result.path("version").asText()).isEqualTo("1"); save(state);
    }
    @Test void failedAndOtherUsersCannotDownloadOrSelectAnotherOwner() throws Exception {
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
    @Test void unpublishedEditsStillDownloadPreviousPublishedBuild() throws Exception {
        ObjectNode state = saved(); Account creator = creator(state); long project = state.path("projectId").asLong();
        byte[] next = zip("new full build for current publication"); upload(project, creator, "2", next); page(project, creator, "Updated title", 1500);
        state.put("nextZip", Base64.getEncoder().encodeToString(next));
        JsonNode result = ticket(state, buyer(state)); assertThat(result.path("version").asText()).isEqualTo("1");
        verifyZip(result, Base64.getDecoder().decode(state.path("zip").asText())); save(state);
    }
    @Test void newlyPublishedBuildUsesSamePermanentRight() throws Exception {
        ObjectNode state = saved(); approveAndPublish(state.path("projectId").asLong(), creator(state));
        JsonNode result = ticket(state, buyer(state)); assertThat(result.path("version").asText()).isEqualTo("2");
        verifyZip(result, Base64.getDecoder().decode(state.path("nextZip").asText())); stableLibrary(state, buyer(state));
    }
    @Test void expiresAndReissuesTheActualStorageUrl() throws Exception {
        ObjectNode state = saved(); Account buyer = buyer(state); JsonNode original = ticket(state, buyer);
        Instant expires = Instant.parse(original.path("expiresAt").asText());
        Thread.sleep(Math.max(1, Duration.between(Instant.now(), expires.plusSeconds(1)).toMillis()));
        rawDownload(original.path("downloadUrl").asText(), 403);
        JsonNode fresh = ticket(state, buyer); verifyZip(fresh, Base64.getDecoder().decode(state.path("nextZip").asText()));
        stableLibrary(state, buyer);
    }
    @Test void prepareFreshUrlBeforeOutage() throws Exception {
        ObjectNode state = saved(); state.set("activeTicket", ticket(state, buyer(state))); save(state);
    }
    @Test void licenseOutageRejectsNewIssuanceButExistingUrlWorks() throws Exception {
        ObjectNode state = saved(); assertThat(Stove.gateway.get(ticketPath(state), buyer(state).headers()).status()).isEqualTo(503);
        verifyZip(state.path("activeTicket"), Base64.getDecoder().decode(state.path("nextZip").asText()));
    }
    @Test void catalogOutagePreservesExistingFileAndLibrary() throws Exception {
        ObjectNode state = saved(); assertThat(Stove.gateway.get(ticketPath(state), buyer(state).headers()).status()).isEqualTo(503);
        verifyZip(state.path("activeTicket"), Base64.getDecoder().decode(state.path("nextZip").asText())); stableLibrary(state, buyer(state));
    }
    @Test void storageOutageRejectsIssuance() throws Exception {
        ObjectNode state = saved(); assertThat(Stove.gateway.get(ticketPath(state), buyer(state).headers()).status()).isEqualTo(503); stableLibrary(state, buyer(state));
    }
    @Test void missingOrChangedFileRejectsIssuance() throws Exception {
        ObjectNode state = saved(); assertThat(Stove.gateway.get(ticketPath(state), buyer(state).headers()).status()).isEqualTo(409); stableLibrary(state, buyer(state));
    }
    @Test void restoredFileAndStatelessRestartRecoverDownloads() throws Exception {
        ObjectNode state = saved(); JsonNode result = ticket(state, buyer(state));
        verifyZip(result, Base64.getDecoder().decode(state.path("nextZip").asText())); stableLibrary(state, buyer(state));
    }
}
