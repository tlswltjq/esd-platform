package com.stove.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.common.contract.payload.OrderPlacedEvent;
import com.stove.e2e.ReviewJourney.Account;
import com.stove.e2e.ReviewJourney.Project;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;

/** 한 스택에서 걸음을 순서대로 밟고, 걸음 사이에 실제 프로세스를 다시 띄운다. Payment는 띄우지 않는다. */
@Tag("g4-operations")
@ExtendWith(LocalStackExtension.class)
@LocalStackApps({StackApp.AUTH, StackApp.STUDIO, StackApp.WEB_BFF, StackApp.GATEWAY, StackApp.REVIEW, StackApp.CATALOG,
        StackApp.STORE, StackApp.ORDER, StackApp.LICENSE, StackApp.DOWNLOAD})
@LocalStackEnvironment({
        // 자동 대조와 초기화 재시도를 기다릴 수 있게 주기를 줄인다.
        "STORE_SYNC_RECONCILE_INTERVAL=PT2S", "STORE_SYNC_RETRY_DELAY=PT1S", "STORE_SYNC_POLL_INTERVAL_MS=100",
        "DOWNLOAD_BUILD_URL_TTL=PT15M",
        // Catalog가 처음부터 읽기 계정(차단 태그 확인)과 태그 전용 계정(다운로드 차단)을 갖는다.
        "STORAGE_BUCKET=" + LocalStack.BUILD_BUCKET,
        "DOWNLOAD_STORAGE_ACCESS_KEY=download-reader", "DOWNLOAD_STORAGE_SECRET_KEY=" + LocalStack.READER_PASSWORD,
        "CATALOG_DOWNLOAD_STORAGE_ENABLED=true",
        "CATALOG_DOWNLOAD_STORAGE_ENDPOINT=" + LocalStack.STORAGE_ENDPOINT_TOKEN,
        "CATALOG_DOWNLOAD_STORAGE_BUCKET=" + LocalStack.BUILD_BUCKET,
        "CATALOG_DOWNLOAD_STORAGE_ACCESS_KEY=catalog-tagger",
        "CATALOG_DOWNLOAD_STORAGE_SECRET_KEY=" + LocalStack.READER_PASSWORD})
class G4OperationsFlowTest {
    private static void publishesAndPreparesOwnedAndPendingOrders() throws Exception {
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
        Awaitility.await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
            assertThat(Stove.gateway.get("/api/v1/orders/" + number, buyer.headers()).data().path("status").asText()).isEqualTo("PAID"));
        ReviewJourney.registeredLibrary(buyer, code(state));
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

    private static void genreOnlyUpdateRequiresApprovalAndAndSearchMatchesAll() throws Exception {
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

    private static void withdrawsAnUpdateAndQueuesApprovedReplacement() throws Exception {
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

    private static void verifiesPublishedUpdateAndPermanentRight() throws Exception {
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

    private static void enforcesIndependentSalesAndListingControls() throws Exception {
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

    private static void restrictsHistoricalUrlsAndNewlyPublishedBuildIndependently() throws Exception {
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

    private static void hiddenProjectionSurvivesHttpBootstrap() throws Exception {
        ObjectNode state = saved();
        assertThat(matches(state.path("controlCode").asText())).isEqualTo(state.path("controlProduct"));
        hidden(state);
        ticket(state, actor(state, "buyerEmail"), "3", "zip3");
        stableRight(state);
    }

    private static void automaticReconciliationRepairsMissingAndSameVersionProjection() throws Exception {
        hiddenProjectionSurvivesHttpBootstrap();
    }

    private static void servesCachedSnapshotDuringCatalogOutage() throws Exception {
        ObjectNode state = saved();
        cachedControl(state);
        hiddenStore(state);
        assertThat(Stove.gateway.get(download(state), actor(state, "buyerEmail").headers()).status()).isEqualTo(503);
        raw(state.path("ticket3"), 200);
        stableRight(state);
    }

    private static void failedBootstrapIsUnavailable() {
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            E2eClient.Response response = Stove.gateway.get("/api/v1/storefront/products");
            assertThat(response.status()).isEqualTo(503);
            assertThat(response.errorCode()).isEqualTo("STORE_NOT_READY");
        });
    }

    private static void bootstrapRetryRecoversBothVisibilityStates() throws Exception {
        hiddenProjectionSurvivesHttpBootstrap();
    }

    private static void restoresPublicAccessAndCurrentPurchaseSnapshot() throws Exception {
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
        // 결정 89: 이미 가진 상품도 새 주문은 받는다(결제하지 않아 보관함·라이브러리는 그대로다).
        JsonNode repurchase = createOrder(state, actor(state, "buyerEmail"), 200).data();
        assertThat(repurchase.path("orderNo").asText()).isNotEqualTo(state.path("paidOrder").asText());
        assertThat(repurchase.path("status").asText()).isEqualTo("PENDING");
        ticket(state, actor(state, "buyerEmail"), "3", "zip3");
        stableRight(state);
        state.set("finalProduct", current);
        state.put("latestPendingOrder", order.path("orderNo").asText());
        save(state);
    }

    /** 앞 걸음이 만든 상품·주문·티켓. 예전에는 실행기가 걸음마다 테스트를 따로 불러 파일로 넘겼다. */
    private static String journey;

    private static ObjectNode saved() throws Exception {
        return (ObjectNode) ReviewJourney.JSON.readTree(journey);
    }

    private static void save(ObjectNode state) throws Exception {
        journey = ReviewJourney.JSON.writeValueAsString(state);
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

    /** Kafka 리스너를 끈 Store: 사본은 HTTP 초기화와 자동 대조로만 맞춰진다. */
    private static final String WITHOUT_LISTENER = "--spring.kafka.listener.auto-startup=false";

    /** 사본에 두 상품이 있고, 노출을 끈 주 상품이 현재 빌드(3)로 남아 있는지 본다. */
    private static void requireProjection(LocalStack stack, String mainCode) {
        List<List<String>> rows = stack.sqlRows("SELECT product_code,listed,catalog_version,content_version,build_version "
                + "FROM stove_store.store_product ORDER BY product_code");
        assertThat(rows).as("Store 사본의 상품 수").hasSize(2);
        List<List<String>> hidden = rows.stream().filter(row -> row.get(0).equals(mainCode)).toList();
        assertThat(hidden).as("노출을 끈 주 상품").hasSize(1);
        assertThat(hidden.get(0).get(1)).as("노출 여부").isEqualTo("0");
        assertThat(hidden.get(0).get(4)).as("현재 빌드").isEqualTo("3");
    }

    private static String generatedCode(String code) {
        // 테스트가 만든 UUID 상품 코드만 SQL에 넣는다.
        assertThat(code).as("생성한 상품 코드").matches("[A-Za-z0-9-]+");
        return code;
    }

    /**
     * 출시·철회부터 Store 복구, 판매·노출·다운로드 제어, 기존 구매권까지 잇는 운영 여정. Payment 없이 결제 결과는 Kafka 계약
     * 픽스처로 넣는다(PG 호출 없음). 테스트가 Catalog·Store를 직접 멈추고 되살리고, Store 사본을 직접 어긋나게 한 뒤 자동
     * 대조가 고치는지 본다(결정 e2e-stack-in-junit).
     */
    @Test
    void operationsSurviveWithdrawalsControlsProjectionDamageAndCatalogOutage(LocalStack stack) throws Exception {
        publishesAndPreparesOwnedAndPendingOrders();
        genreOnlyUpdateRequiresApprovalAndAndSearchMatchesAll();

        // 자동 공개 작업자를 끈 Catalog: 대기 중인 출시를 철회하고 승인된 대체본을 줄 세운다.
        stack.restart(StackApp.CATALOG, "--stove.catalog.publication.worker-enabled=false");
        withdrawsAnUpdateAndQueuesApprovedReplacement();
        stack.restart(StackApp.CATALOG);
        verifiesPublishedUpdateAndPermanentRight();
        enforcesIndependentSalesAndListingControls();
        restrictsHistoricalUrlsAndNewlyPublishedBuildIndependently();

        // 사본을 비우고 리스너 없이 다시 띄운다: 노출을 끈 상품도 HTTP 초기화로 돌아온다.
        stack.stop(StackApp.STORE);
        stack.sql("DELETE FROM stove_store.store_product");
        stack.start(StackApp.STORE, WITHOUT_LISTENER);
        hiddenProjectionSurvivesHttpBootstrap();
        ObjectNode state = saved();
        String mainCode = generatedCode(state.path("code").asText());
        String controlCode = generatedCode(state.path("controlCode").asText());
        requireProjection(stack, mainCode);

        // 사본을 직접 어긋나게 한다: 대조군 상품은 지우고, 주 상품은 같은 버전인 채 값·노출·장르를 바꾼다.
        long auditStart = Long.parseLong(stack.sql("SELECT COALESCE(MAX(id),0) FROM stove_store.store_reconciliation"));
        List<List<String>> damaged = stack.sqlRows("START TRANSACTION; "
                + "DELETE FROM stove_store.store_product WHERE product_code='" + controlCode + "'; "
                + "UPDATE stove_store.store_product SET price=1,listed=TRUE,title='Injected projection mismatch' "
                + "WHERE product_code='" + mainCode + "'; "
                + "DELETE FROM stove_store.store_product_genre WHERE product_id="
                + "(SELECT product_id FROM stove_store.store_product WHERE product_code='" + mainCode + "'); "
                + "INSERT INTO stove_store.store_product_genre SELECT product_id,'PUZZLE' FROM stove_store.store_product "
                + "WHERE product_code='" + mainCode + "'; "
                + "SELECT catalog_version,price,listed FROM stove_store.store_product WHERE product_code='" + mainCode + "'; "
                + "COMMIT;");
        assertThat(damaged).as("어긋나게 만든 주 상품").hasSize(1);
        String version = damaged.get(0).get(0);
        assertThat(damaged.get(0).subList(1, 3)).isEqualTo(List.of("1", "1"));
        automaticReconciliationRepairsMissingAndSameVersionProjection();
        assertThat(stack.sqlRows("SELECT catalog_version,price,listed FROM stove_store.store_product WHERE product_code='"
                + mainCode + "'")).as("같은 버전으로 정본 값을 되찾는다").isEqualTo(List.of(List.of(version, "2500", "0")));
        List<List<String>> repairs = stack.sqlRows("SELECT action,product_code,"
                + "JSON_UNQUOTE(JSON_EXTRACT(before_snapshot,'$.price')),JSON_UNQUOTE(JSON_EXTRACT(after_snapshot,'$.price')),"
                + "catalog_version,JSON_EXTRACT(before_snapshot,'$.genres'),JSON_EXTRACT(after_snapshot,'$.genres') "
                + "FROM stove_store.store_reconciliation WHERE id>" + auditStart + " ORDER BY id");
        assertThat(repairs).as("값을 고친 대조 기록").anySatisfy(row -> {
            assertThat(row.subList(0, 5)).isEqualTo(List.of("REPAIRED", mainCode, "1", "2500", version));
        });
        assertThat(repairs).as("장르를 고친 대조 기록").anySatisfy(row -> {
            assertThat(row.subList(0, 2)).isEqualTo(List.of("REPAIRED", mainCode));
            assertThat(ReviewJourney.JSON.readTree(row.get(5))).isEqualTo(ReviewJourney.JSON.readTree("[\"PUZZLE\"]"));
            assertThat(ReviewJourney.JSON.readTree(row.get(6)))
                    .isEqualTo(ReviewJourney.JSON.readTree("[\"ACTION\",\"RPG\"]"));
        });
        assertThat(repairs).as("빠진 상품을 다시 넣은 대조 기록").anySatisfy(row ->
                assertThat(row.subList(0, 2)).isEqualTo(List.of("INSERTED", controlCode)));

        // Catalog가 없어도 가진 사본으로 답하고, Catalog 없이 다시 뜨면 준비되지 않았다고 답한다.
        stack.stop(StackApp.CATALOG);
        servesCachedSnapshotDuringCatalogOutage();
        stack.restart(StackApp.STORE, WITHOUT_LISTENER);
        failedBootstrapIsUnavailable();
        // Catalog가 돌아오면 Store는 다시 띄우지 않아도 스스로 초기화를 마친다.
        long recovering = stack.pid(StackApp.STORE);
        stack.start(StackApp.CATALOG);
        bootstrapRetryRecoversBothVisibilityStates();
        assertThat(stack.pid(StackApp.STORE)).as("자동 복구 동안 Store를 다시 띄우지 않았다").isEqualTo(recovering);
        requireProjection(stack, mainCode);

        stack.restart(StackApp.STORE);
        restoresPublicAccessAndCurrentPurchaseSnapshot();
    }
}
