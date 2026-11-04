package com.stove.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.common.kafka.contract.payload.OrderPlacedEvent;
import com.stove.e2e.ReviewJourney.Account;
import com.stove.e2e.ReviewJourney.Project;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Each phase runs against the same disposable stack, with real process restarts between phases. */
@Tag("g4-operations")
class G4OperationsFlowTest {
    @Test
    void publishesAndPreparesOwnedAndPendingOrders() throws Exception {
        Account creator = ReviewJourney.account(true);
        Account buyer = ReviewJourney.account(false);
        Account pendingSeller = ReviewJourney.account(false);
        Account pendingAdmin = ReviewJourney.account(false);
        Account fresh = ReviewJourney.account(false);
        Project project = ReviewJourney.project(creator);
        Project control = ReviewJourney.project(creator);
        String suffix = UUID.randomUUID().toString();
        ReviewJourney.page(project.id(), creator, "G4Main-" + suffix, 1000);
        ReviewJourney.page(control.id(), creator, "G4Control-" + suffix, 700, List.of("ACTION"));
        publish(project.id(), creator);
        publish(control.id(), creator);
        ObjectNode state = ReviewJourney.JSON.createObjectNode();
        state.put("creatorEmail", creator.email()).put("buyerEmail", buyer.email())
                .put("sellerPendingEmail", pendingSeller.email()).put("adminPendingEmail", pendingAdmin.email())
                .put("freshEmail", fresh.email()).put("project", project.id()).put("controlProject", control.id())
                .put("code", ReviewJourney.data(project.id(), creator).path("productCode").asText())
                .put("controlCode", ReviewJourney.data(control.id(), creator).path("productCode").asText())
                .put("zip1", Base64.getEncoder().encodeToString(project.zip()));
        state.set("firstProduct", matches(state.path("code").asText()));
        state.set("controlProduct", matches(state.path("controlCode").asText()));
        JsonNode order = createOrder(state, buyer, 200).data();
        String number = order.path("orderNo").asText();
        OrderPlacedEvent placed = G4PaymentFixture.placed(number);
        Map<String, Object> admission = G4PaymentFixture.admission(placed);
        assertThat(G4PaymentFixture.admit(number, admission).status()).isEqualTo(200);
        G4PaymentFixture.complete(placed, (String) admission.get("paymentId"));
        Awaitility.await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
            assertThat(Stove.gateway.get("/api/v1/orders/" + number, buyer.headers()).data().path("status").asText()).isEqualTo("PAID");
            assertThat(Stove.gateway.get(library(state), buyer.headers()).status()).isEqualTo(200);
        });
        state.put("paidOrder", number).put("paymentEvidence", "synthetic OrderPaymentCompleted after real protected admission; no PG call");
        state.set("license", Stove.gateway.get(library(state), buyer.headers()).data());
        assertThat(state.path("license").path("status").asText()).isEqualTo("ACTIVE");
        assertThat(state.path("license").path("productId")).isEqualTo(state.path("firstProduct").path("productId"));
        state.set("firstOrder", order);
        state.set("ticket1", ticket(state, buyer, "1", "zip1"));
        JsonNode sellerOrder = createOrder(state, pendingSeller, 200).data();
        JsonNode adminOrder = createOrder(state, pendingAdmin, 200).data();
        state.put("sellerOrder", sellerOrder.path("orderNo").asText()).put("adminOrder", adminOrder.path("orderNo").asText());
        state.set("sellerAdmission", ReviewJourney.JSON.valueToTree(G4PaymentFixture.admission(G4PaymentFixture.placed(state.path("sellerOrder").asText()))));
        state.set("adminAdmission", ReviewJourney.JSON.valueToTree(G4PaymentFixture.admission(G4PaymentFixture.placed(state.path("adminOrder").asText()))));
        assertThat(Stove.gateway.get(download(state)).status()).isEqualTo(401);
        assertThat(Stove.gateway.get(download(state), fresh.headers()).status()).isEqualTo(403);
        Map<String, String> forged = new HashMap<>(fresh.headers());
        forged.put("X-Member-Id", buyer.subject());
        assertThat(Stove.gateway.get(download(state), forged).status()).isEqualTo(403);
        save(state);
    }

    @Test
    void genreOnlyUpdateRequiresApprovalAndAndSearchMatchesAll() throws Exception {
        ObjectNode state = saved();
        Account creator = actor(state, "creatorEmail");
        long project = state.path("project").asLong();
        E2eClient.Response vocabulary = Stove.gateway.get("/api/v1/storefront/genres");
        assertThat(vocabulary.status()).isEqualTo(200);
        assertThat(vocabulary.data().size()).isEqualTo(10);
        assertThat(Stove.gateway.get("/api/v1/studio/genres", creator.headers()).data()).isEqualTo(vocabulary.data());
        assertThat(Stove.gateway.get("/api/v1/studio/genres").status()).isEqualTo(401);
        assertThat(Stove.gateway.get("/api/v1/studio/genres", actor(state, "buyerEmail").headers()).status()).isEqualTo(403);
        JsonNode filtered = Stove.gateway.get("/api/v1/storefront/products?genres=ACTION&genres=RPG&size=1").data();
        assertThat(filtered.path("totalElements").asLong()).isEqualTo(1);
        assertThat(filtered.path("content").get(0).path("productCode").asText()).isEqualTo(code(state));
        assertThat(Stove.gateway.get("/api/v1/storefront/products?genres=UNKNOWN").status()).isEqualTo(400);
        JsonNode before = ReviewJourney.data(project, creator);
        List<String> genres = List.of("ACTION", "PUZZLE");
        ReviewJourney.page(project, creator, state.path("firstProduct").path("title").asText(), 1000, genres);
        JsonNode changed = ReviewJourney.data(project, creator);
        assertThat(changed.path("contentVersion").asLong()).isEqualTo(before.path("contentVersion").asLong() + 1);
        assertThat(changed.path("currentBuildId")).isEqualTo(before.path("currentBuildId"));
        assertThat(changed.path("releaseEligible").asBoolean()).isFalse();
        assertThat(Stove.gateway.post(ReviewJourney.path(project) + "/release-requests",
                ReviewJourney.submission(ReviewJourney.path(project), creator.headers()), creator.headers()).status()).isEqualTo(409);
        assertThat(matches(code(state))).isEqualTo(state.path("firstProduct"));
        Account reviewer = reviewer();
        long review = ReviewJourney.request(project, creator, reviewer);
        JsonNode submitted = Stove.gateway.get(ReviewJourney.reviewPath(review), reviewer.headers()).data().path("snapshot");
        assertThat(submitted.path("genres")).isEqualTo(ReviewJourney.JSON.valueToTree(genres));
        assertThat(Stove.gateway.post(ReviewJourney.reviewPath(review) + "/approve", null, reviewer.headers()).status()).isEqualTo(200);
        ReviewJourney.waitForResult(project, review, creator, "APPROVED");
        history(project, "release", release(project, creator), creator, "COMPLETED");
        JsonNode current = matches(code(state));
        assertThat(current.path("genres")).isEqualTo(ReviewJourney.JSON.valueToTree(genres));
        assertThat(Stove.gateway.get("/api/v1/storefront/products?genres=ACTION&genres=RPG").data().path("totalElements").asLong()).isZero();
        assertThat(Stove.gateway.get("/api/v1/storefront/products?genres=ACTION&genres=PUZZLE").data().path("totalElements").asLong()).isEqualTo(1);
        ticket(state, actor(state, "buyerEmail"), "1", "zip1");
        state.set("firstProduct", current);
        stableRight(state);
        save(state);
    }

    @Test
    void withdrawsAnUpdateAndQueuesApprovedReplacement() throws Exception {
        ObjectNode state = saved();
        Account creator = actor(state, "creatorEmail");
        Account reviewer = reviewer();
        long project = state.path("project").asLong();
        byte[] zip = ReviewJourney.zip("G4 approved second build");
        state.put("zip2", Base64.getEncoder().encodeToString(zip));
        ReviewJourney.upload(project, creator, "2", zip);
        ReviewJourney.page(project, creator, "G4Main-approved-update", 2000);
        assertThat(matches(code(state))).isEqualTo(state.path("firstProduct"));
        ticket(state, actor(state, "buyerEmail"), "1", "zip1");
        long withdrawnReview = ReviewJourney.request(project, creator, reviewer);
        withdraw(project, "review", withdrawnReview, creator);
        ReviewJourney.waitForResult(project, withdrawnReview, creator, "WITHDRAWN");
        approve(project, creator);
        long first = release(project, creator);
        queued(first);
        withdraw(project, "release", first, creator);
        history(project, "release", first, creator, "WITHDRAWN");
        assertThat(matches(code(state))).isEqualTo(state.path("firstProduct"));
        ticket(state, actor(state, "buyerEmail"), "1", "zip1");
        long replacement = release(project, creator);
        queued(replacement);
        state.put("withdrawnRelease", first).put("survivingRelease", replacement);
        ReviewJourney.page(project, creator, "G4Main-unreviewed-draft", 9000);
        assertThat(ReviewJourney.data(project, creator).path("activeReleaseRequest").path("id").asLong()).isEqualTo(replacement);
        save(state);
    }

    @Test
    void verifiesPublishedUpdateAndPermanentRight() throws Exception {
        ObjectNode state = saved();
        Account creator = actor(state, "creatorEmail");
        history(state.path("project").asLong(), "release", state.path("survivingRelease").asLong(), creator, "COMPLETED");
        history(state.path("project").asLong(), "release", state.path("withdrawnRelease").asLong(), creator, "WITHDRAWN");
        JsonNode current = matches(code(state));
        assertThat(current.path("title").asText()).isEqualTo("G4Main-approved-update");
        assertThat(current.path("price").asLong()).isEqualTo(2000);
        assertThat(current.path("productId").asLong()).isPositive().isEqualTo(state.path("firstProduct").path("productId").asLong());
        assertThat(current.path("publishedAt")).isEqualTo(state.path("firstProduct").path("publishedAt"));
        state.set("secondProduct", current);
        state.set("ticket2", ticket(state, actor(state, "buyerEmail"), "2", "zip2"));
        String root = ReviewJourney.path(state.path("project").asLong());
        Account member = actor(state, "buyerEmail");
        for (String path : List.of("/api/v1/studio/projects", root + "/builds", root + "/review-requests", root + "/release-requests")) {
            E2eClient.Response first = Stove.gateway.get(path + "?size=1", creator.headers());
            E2eClient.Response second = Stove.gateway.get(path + "?page=1&size=1", creator.headers());
            assertThat(first.status()).as(path).isEqualTo(200);
            assertThat(second.status()).as(path).isEqualTo(200);
            assertThat(first.data().path("items").size()).as(path).isEqualTo(1);
            assertThat(second.data().path("items").size()).as(path).isEqualTo(1);
            assertThat(first.data().path("totalElements").asLong()).as(path).isGreaterThanOrEqualTo(2);
            assertThat(first.data().path("totalPages")).isEqualTo(first.data().path("totalElements"));
            assertThat(first.data().path("items").get(0).path("id").asLong())
                    .isGreaterThan(second.data().path("items").get(0).path("id").asLong());
            assertThat(Stove.gateway.get(path + "?size=101", creator.headers()).status()).isEqualTo(400);
            assertThat(Stove.gateway.get(path).status()).isEqualTo(401);
            assertThat(Stove.gateway.get(path, member.headers()).status()).isEqualTo(403);
        }
        Account other = ReviewJourney.account(true);
        E2eClient.Response others = Stove.gateway.get("/api/v1/studio/projects", other.headers());
        assertThat(others.status()).isEqualTo(200);
        assertThat(others.data().path("totalElements").asLong()).isZero();
        assertThat(Stove.gateway.get(root + "/review-requests", other.headers()).status()).isEqualTo(403);
        stableRight(state);
        save(state);
    }

    @Test
    void enforcesIndependentSalesAndListingControls() throws Exception {
        ObjectNode state = saved();
        Account creator = actor(state, "creatorEmail");
        Account admin = admin();
        Account fresh = actor(state, "freshEmail");
        change(state, "sales", false, "saleStatus", "STOPPED", creator);
        hidden(state);
        assertThat(createOrder(state, fresh, 404).errorCode()).isEqualTo("NOT_FOUND");
        ticket(state, actor(state, "buyerEmail"), "2", "zip2");
        E2eClient.Response admitted = G4PaymentFixture.admit(state.path("sellerOrder").asText(), state.path("sellerAdmission"));
        assertThat(admitted.status()).isEqualTo(200);
        state.set("sellerReceipt", admitted.data());
        change(state, "listing", false, "preference", "LISTED", creator);
        assertThat(matches(code(state)).path("saleStatus").asText()).isEqualTo("STOPPED");
        assertThat(createOrder(state, fresh, 409).errorCode()).isEqualTo("PRODUCT_NOT_ON_SALE");
        change(state, "sales", true, "restricted", true, admin);
        assertThat(matches(code(state)).path("saleStatus").asText()).isEqualTo("BLOCKED");
        E2eClient.Response denied = G4PaymentFixture.admit(state.path("adminOrder").asText(), state.path("adminAdmission"));
        assertThat(denied.status()).isEqualTo(409);
        assertThat(denied.errorCode()).isEqualTo("PRODUCT_SALE_RESTRICTED");
        E2eClient.Response replay = G4PaymentFixture.admit(state.path("sellerOrder").asText(), state.path("sellerAdmission"));
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.data()).isEqualTo(state.path("sellerReceipt"));
        JsonNode sale = change(state, "sales", false, "saleStatus", "ON_SALE", creator);
        assertThat(sale.path("saleStatus").asText()).isEqualTo("BLOCKED");
        change(state, "listing", true, "hidden", true, admin);
        hidden(state);
        assertThat(createOrder(state, fresh, 404).status()).isEqualTo(404);
        assertThat(Stove.gateway.get(publicPath(state), admin.headers()).status()).isEqualTo(404);
        assertThat(Stove.gateway.get(sellerPath(state, "sales"), fresh.headers()).status()).isEqualTo(403);
        Account other = ReviewJourney.account(true);
        E2eClient.Response otherOwner = Stove.gateway.get(sellerPath(state, "listing"), other.headers());
        assertThat(otherOwner.status()).isEqualTo(404);
        assertThat(otherOwner.errorCode()).isEqualTo("NOT_FOUND");
        assertThat(Stove.gateway.get(adminPath(state, "downloads"), creator.headers()).status()).isEqualTo(403);
        ticket(state, actor(state, "buyerEmail"), "2", "zip2");
        stableRight(state);
        save(state);
    }

    @Test
    void restrictsHistoricalUrlsAndNewlyPublishedBuildIndependently() throws Exception {
        ObjectNode state = saved();
        Account creator = actor(state, "creatorEmail");
        restrictDownload(state, true);
        assertThat(Stove.gateway.get(download(state), actor(state, "buyerEmail").headers()).status()).isEqualTo(403);
        raw(state.path("ticket1"), 403);
        raw(state.path("ticket2"), 403);
        long project = state.path("project").asLong();
        byte[] third = ReviewJourney.zip("G4 third build during independent restriction");
        state.put("zip3", Base64.getEncoder().encodeToString(third));
        ReviewJourney.upload(project, creator, "3", third);
        ReviewJourney.page(project, creator, "G4Main-restricted-update", 2500);
        publish(project, creator);
        hidden(state);
        awaitStorage(state, true);
        assertThat(Stove.gateway.get(download(state), actor(state, "buyerEmail").headers()).status()).isEqualTo(403);
        raw(state.path("ticket1"), 403);
        raw(state.path("ticket2"), 403);
        restrictDownload(state, false);
        state.set("ticket3", ticket(state, actor(state, "buyerEmail"), "3", "zip3"));
        raw(state.path("ticket1"), 200);
        raw(state.path("ticket2"), 200);
        assertThat(Stove.gateway.get(sellerPath(state, "sales"), creator.headers()).data().path("saleStatus").asText()).isEqualTo("BLOCKED");
        change(state, "listing", false, "preference", "UNLISTED", creator);
        JsonNode listing = change(state, "listing", true, "hidden", false, admin());
        assertThat(listing.path("listed").asBoolean()).isFalse();
        change(state, "sales", true, "restricted", false, admin());
        hidden(state);
        stableRight(state);
        save(state);
    }

    @Test
    void hiddenProjectionSurvivesHttpBootstrap() throws Exception {
        ObjectNode state = saved();
        assertThat(matches(state.path("controlCode").asText())).isEqualTo(state.path("controlProduct"));
        hidden(state);
        ticket(state, actor(state, "buyerEmail"), "3", "zip3");
        stableRight(state);
    }

    @Test
    void automaticReconciliationRepairsMissingAndSameVersionProjection() throws Exception {
        hiddenProjectionSurvivesHttpBootstrap();
    }

    @Test
    void servesCachedSnapshotDuringCatalogOutage() throws Exception {
        ObjectNode state = saved();
        cachedControl(state);
        hiddenStore(state);
        assertThat(Stove.gateway.get(download(state), actor(state, "buyerEmail").headers()).status()).isEqualTo(503);
        raw(state.path("ticket3"), 200);
        stableRight(state);
    }

    @Test
    void failedBootstrapIsUnavailable() {
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            E2eClient.Response response = Stove.gateway.get("/api/v1/storefront/products");
            assertThat(response.status()).isEqualTo(503);
            assertThat(response.errorCode()).isEqualTo("STORE_NOT_READY");
        });
    }

    @Test
    void bootstrapRetryRecoversBothVisibilityStates() throws Exception {
        hiddenProjectionSurvivesHttpBootstrap();
    }

    @Test
    void restoresPublicAccessAndCurrentPurchaseSnapshot() throws Exception {
        ObjectNode state = saved();
        change(state, "listing", false, "preference", "AUTO", actor(state, "creatorEmail"));
        JsonNode current = matches(code(state));
        assertThat(current.path("title").asText()).isEqualTo("G4Main-restricted-update");
        assertThat(current.path("price").asLong()).isEqualTo(2500);
        assertThat(current.path("productId").asLong()).isPositive().isEqualTo(state.path("firstProduct").path("productId").asLong());
        assertThat(current.path("publishedAt")).isEqualTo(state.path("firstProduct").path("publishedAt"));
        Account fresh = actor(state, "freshEmail");
        JsonNode order = createOrder(state, fresh, 200).data();
        assertThat(order.path("amount").asLong()).isEqualTo(2500);
        assertThat(order.path("contentVersion")).isEqualTo(current.path("contentVersion"));
        assertThat(order.path("status").asText()).isEqualTo("PENDING");
        assertThat(Stove.gateway.get(download(state), fresh.headers()).status()).isEqualTo(403);
        assertThat(createOrder(state, actor(state, "buyerEmail"), 409).errorCode()).isEqualTo("PRODUCT_ALREADY_PURCHASED");
        ticket(state, actor(state, "buyerEmail"), "3", "zip3");
        stableRight(state);
        state.set("finalProduct", current);
        state.put("latestPendingOrder", order.path("orderNo").asText());
        save(state);
    }

    private static ObjectNode saved() throws Exception {
        return (ObjectNode) ReviewJourney.JSON.readTree(Files.readString(evidence()));
    }

    private static Path evidence() { return Path.of(System.getProperty("stove.e2e.g4-evidence")); }

    private static void save(ObjectNode state) throws Exception {
        if (!Files.exists(evidence())) Files.createFile(evidence(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(evidence(), ReviewJourney.JSON.writeValueAsString(state));
    }

    private static Account actor(ObjectNode state, String field) throws Exception { return ReviewJourney.login(state.path(field).asText(), "creator-password-1234"); }
    private static Account reviewer() throws Exception { return ReviewJourney.login(ReviewJourney.REVIEWER_EMAIL, ReviewJourney.REVIEWER_PASSWORD); }
    private static Account admin() throws Exception { return ReviewJourney.login(ReviewJourney.ADMIN_EMAIL, ReviewJourney.ADMIN_PASSWORD); }
    private static String code(ObjectNode state) { return state.path("code").asText(); }
    private static String publicPath(ObjectNode state) { return "/api/v1/products/by-code/" + code(state); }
    private static String storePath(ObjectNode state) { return "/api/v1/storefront/products/" + code(state); }
    private static String library(ObjectNode state) { return "/api/v1/library/" + code(state); }
    private static String download(ObjectNode state) { return "/api/v1/downloads/" + code(state) + "/ticket"; }
    private static String sellerPath(ObjectNode state, String area) { return publicPath(state) + "/" + area; }
    private static String adminPath(ObjectNode state, String area) { return "/api/v1/admin/products/" + code(state) + "/" + area + "/restriction"; }

    private static void approve(long project, Account creator) throws Exception {
        Account reviewer = reviewer();
        long review = ReviewJourney.request(project, creator, reviewer);
        assertThat(Stove.gateway.post(ReviewJourney.reviewPath(review) + "/approve", null, reviewer.headers()).status()).isEqualTo(200);
        ReviewJourney.waitForResult(project, review, creator, "APPROVED");
    }

    private static long release(long project, Account creator) {
        String path = ReviewJourney.path(project);
        E2eClient.Response response = Stove.gateway.post(path + "/release-requests", ReviewJourney.submission(path, creator.headers()), creator.headers());
        assertThat(response.status()).isEqualTo(202);
        return response.data().path("id").asLong();
    }

    private static void publish(long project, Account creator) throws Exception {
        approve(project, creator);
        history(project, "release", release(project, creator), creator, "COMPLETED");
    }

    private static void history(long project, String kind, long request, Account creator, String status) {
        Awaitility.await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertThat(ReviewJourney.listItems(ReviewJourney.path(project) + "/" + kind + "-requests", creator.headers()))
                        .anySatisfy(item -> {
                            assertThat(item.path("id").asLong()).isEqualTo(request);
                            assertThat(item.path("status").asText()).isEqualTo(status);
                        }));
    }

    private static void withdraw(long project, String kind, long request, Account creator) {
        String path = ReviewJourney.path(project) + "/" + kind + "-requests/" + request + "/withdrawals";
        E2eClient.Response receipt = Stove.gateway.post(path, Map.of("requestId", UUID.randomUUID().toString(), "reason", "G4 verification"), creator.headers());
        assertThat(receipt.status()).isEqualTo(202);
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(Stove.gateway.get(path + "/" + receipt.data().path("id").asLong(), creator.headers()).data().path("state").asText()).isEqualTo("WITHDRAWN"));
    }

    private static void queued(long request) throws Exception {
        Account admin = admin();
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(Stove.gateway.get("/api/v1/admin/catalog-release-jobs/" + request, admin.headers()).data().path("state").asText()).isEqualTo("PENDING"));
    }

    private static JsonNode matches(String code) {
        String detail = "/api/v1/storefront/products/" + code;
        E2eClient.Response catalog = Stove.gateway.get("/api/v1/products/by-code/" + code);
        assertThat(catalog.status()).isEqualTo(200);
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            E2eClient.Response store = Stove.gateway.get(detail);
            assertThat(store.status()).isEqualTo(200);
            assertThat(store.data()).isEqualTo(catalog.data());
            E2eClient.Response search = Stove.gateway.get("/api/v1/storefront/products?q=" + catalog.data().path("title").asText() + "&size=1");
            assertThat(search.status()).isEqualTo(200);
            assertThat(search.data().path("totalElements").asLong()).isEqualTo(1);
            JsonNode summary = search.data().path("content").get(0);
            assertThat(summary).isNotNull();
            summary.fields().forEachRemaining(field -> assertThat(field.getValue()).as(field.getKey()).isEqualTo(catalog.data().path(field.getKey())));
        });
        assertThat(catalog.data().toString()).doesNotContain("storagePath", "ownerSubject", "releaseRequestId");
        return catalog.data();
    }

    private static void hidden(ObjectNode state) {
        assertThat(Stove.gateway.get(publicPath(state)).status()).isEqualTo(404);
        hiddenStore(state);
    }

    private static void hiddenStore(ObjectNode state) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(Stove.gateway.get(storePath(state)).status()).isEqualTo(404);
            E2eClient.Response search = Stove.gateway.get("/api/v1/storefront/products?q=G4Main&size=1");
            assertThat(search.status()).isEqualTo(200);
            assertThat(search.data().path("totalElements").asLong()).isZero();
            E2eClient.Response list = Stove.gateway.get("/api/v1/storefront/products?size=1");
            assertThat(list.status()).isEqualTo(200);
            assertThat(list.data().path("totalElements").asLong()).isEqualTo(1);
            assertThat(list.data().path("content").get(0).path("productCode").asText()).isEqualTo(state.path("controlCode").asText());
        });
    }

    private static void cachedControl(ObjectNode state) {
        E2eClient.Response response = Stove.gateway.get("/api/v1/storefront/products/" + state.path("controlCode").asText());
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.data()).isEqualTo(state.path("controlProduct"));
    }

    private static JsonNode change(ObjectNode state, String area, boolean platform, String field, Object value, Account actor) {
        String path = platform ? adminPath(state, area) : sellerPath(state, area);
        E2eClient.Response before = Stove.gateway.get(path, actor.headers());
        assertThat(before.status()).isEqualTo(200);
        E2eClient.Response result = Stove.gateway.put(path, Map.of(field, value, "expectedCatalogVersion", before.data().path("catalogVersion").asLong()), actor.headers());
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.headers().getCacheControl()).contains("no-store");
        return result.data();
    }

    private static E2eClient.Response createOrder(ObjectNode state, Account buyer, int expected) {
        JsonNode product = state.has("secondProduct") ? state.path("secondProduct") : state.path("firstProduct");
        E2eClient.Response publicProduct = Stove.gateway.get(publicPath(state));
        if (publicProduct.status() == 200) product = publicProduct.data();
        E2eClient.Response response = Stove.gateway.post("/api/v1/orders", Map.of("requestId", UUID.randomUUID().toString(), "productCode", code(state),
                "expectedContentVersion", product.path("contentVersion").asLong(), "expectedAmount", product.path("price").asLong()), buyer.headers());
        assertThat(response.status()).isEqualTo(expected);
        return response;
    }

    private static JsonNode ticket(ObjectNode state, Account buyer, String version, String zipField) throws Exception {
        E2eClient.Response response = Stove.gateway.get(download(state), buyer.headers());
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers().getCacheControl()).contains("no-store");
        JsonNode ticket = response.data();
        assertThat(ticket.path("version").asText()).isEqualTo(version);
        byte[] expected = Base64.getDecoder().decode(state.path(zipField).asText());
        byte[] actual = raw(ticket, 200);
        assertThat(actual).isEqualTo(expected);
        assertThat(ticket.path("fileSize").asLong()).isEqualTo(expected.length);
        assertThat(ticket.path("checksum").asText()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actual)));
        return ticket;
    }

    private static byte[] raw(JsonNode ticket, int expected) throws Exception {
        // A natural URL expiry must never masquerade as successful immediate restriction.
        assertThat(Instant.parse(ticket.path("expiresAt").asText())).isAfter(Instant.now().plusSeconds(5));
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(ticket.path("downloadUrl").asText()))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(expected);
        return response.body();
    }

    private static void restrictDownload(ObjectNode state, boolean restricted) throws Exception {
        Account admin = admin();
        String path = adminPath(state, "downloads");
        JsonNode current = Stove.gateway.get(path, admin.headers()).data();
        E2eClient.Response response = Stove.gateway.put(path, Map.of("restricted", restricted, "expectedRestrictionVersion", current.path("restrictionVersion").asLong(),
                "reason", "G4 independent build access check"), admin.headers());
        assertThat(response.status()).isEqualTo(202);
        awaitStorage(state, restricted);
    }

    private static void awaitStorage(ObjectNode state, boolean restricted) throws Exception {
        Account admin = admin();
        Awaitility.await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
            E2eClient.Response current = Stove.gateway.get(adminPath(state, "downloads"), admin.headers());
            assertThat(current.status()).isEqualTo(200);
            assertThat(current.data().path("restricted").asBoolean()).isEqualTo(restricted);
            assertThat(current.data().path("storageStatus").asText()).isEqualTo("APPLIED");
            assertThat(current.data().path("downloadUnavailable").asBoolean()).isEqualTo(restricted);
        });
    }

    private static void stableRight(ObjectNode state) throws Exception {
        Account buyer = actor(state, "buyerEmail");
        E2eClient.Response response = Stove.gateway.get(library(state), buyer.headers());
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.data()).isEqualTo(state.path("license"));
        E2eClient.Response order = Stove.gateway.get("/api/v1/orders/" + state.path("paidOrder").asText(), buyer.headers());
        assertThat(order.status()).isEqualTo(200);
        assertThat(order.data().path("status").asText()).isEqualTo("PAID");
        for (String field : new String[] {"productId", "productCode", "title", "contentVersion", "amount", "currency", "createdAt", "expiresAt"})
            assertThat(order.data().path(field)).as("original purchase " + field).isEqualTo(state.path("firstOrder").path(field));
    }
}
