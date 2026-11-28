package com.stove.e2e;

import static com.stove.e2e.DownloadPurchaseFlowTest.approveAndPublish;
import static com.stove.e2e.ReviewJourney.ADMIN_EMAIL;
import static com.stove.e2e.ReviewJourney.ADMIN_PASSWORD;
import static com.stove.e2e.ReviewJourney.REVIEWER_EMAIL;
import static com.stove.e2e.ReviewJourney.REVIEWER_PASSWORD;
import static com.stove.e2e.ReviewJourney.account;
import static com.stove.e2e.ReviewJourney.data;
import static com.stove.e2e.ReviewJourney.login;
import static com.stove.e2e.ReviewJourney.page;
import static com.stove.e2e.ReviewJourney.project;
import static com.stove.e2e.ReviewJourney.registeredLibrary;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.stove.e2e.E2eClient.Response;
import com.stove.e2e.ReviewJourney.Account;
import com.stove.e2e.ReviewJourney.Project;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * 가입·로그인, 역할별 진입 여정(결정 201의 기준선): 실제 BFF 로그인으로 들어온 사용자가 자기 것만 보고, 역할이 허락한
 * 문만 연다.
 *
 * <p>구매는 DB에 넣지 않고 실제 출시·주문 API로 만든다(결정 e2e-stack-in-junit). 그래서 스키마가 바뀌어도 이 여정의
 * 준비 단계는 앱과 함께 따라간다.
 */
@Tag("ownership")
@ExtendWith(LocalStackExtension.class)
class OwnershipIsolationFlowTest {

    private static Purchase first;
    private static Purchase second;
    private static Account reviewer;
    private static Account admin;

    /** 판매자 한 명이 낸 상품 하나와 그것을 산 구매자 한 명. */
    private record Purchase(Account creator, long projectId, Account buyer, String productCode, String orderNo) {}

    @BeforeAll
    static void twoCreatorsPublishAndTwoBuyersPurchase() throws Exception {
        first = purchase("Ownership product 1");
        second = purchase("Ownership product 2");
        reviewer = login(REVIEWER_EMAIL, REVIEWER_PASSWORD);
        admin = login(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    @Test
    void buyersReadOnlyTheirOwnOrdersPaymentsAndLibrary(LocalStack stack) {
        for (Purchase[] pair : List.of(new Purchase[] {first, second}, new Purchase[] {second, first})) {
            Purchase own = pair[0];
            Account other = pair[1].buyer();
            String ownerId = stack.sql("SELECT id FROM stove_auth.user_account WHERE subject='"
                    + own.buyer().subject().replace("'", "''") + "'");
            assertThat(ownerId).as("소유자의 계정 ID").isNotBlank();
            Map<String, String> spoofed = new LinkedHashMap<>(other.headers());
            spoofed.put("X-Member-Id", ownerId);

            for (String path : List.of("/api/v1/orders/" + own.orderNo(), "/api/v1/payments/" + own.orderNo(),
                    "/api/v1/library/" + own.productCode())) {
                Response mine = Stove.gateway.get(path, own.buyer().headers());
                assertThat(mine.status()).as("소유자 %s — %s", path, mine).isEqualTo(200);
                assertThat(mine.data().path("productCode").asText()).as(path).isEqualTo(own.productCode());
                assertThat(Stove.gateway.get(path, other.headers()).status()).as("남의 것 %s", path).isEqualTo(404);
                assertThat(Stove.gateway.get(path, spoofed).status()).as("위조 헤더 %s", path).isEqualTo(404);
            }
            for (String path : List.of("/api/v1/orders", "/api/v1/payments", "/api/v1/library")) {
                Response list = Stove.gateway.get(path, own.buyer().headers());
                assertThat(list.status()).as("목록 %s — %s", path, list).isEqualTo(200);
                JsonNode rows = list.data().path("content");
                assertThat(rows.size()).as("목록 격리 %s — %s", path, list).isEqualTo(1);
                assertThat(rows.get(0).path("productCode").asText()).as(path).isEqualTo(own.productCode());
            }
        }
    }

    @Test
    void creatorsReadOnlyTheirOwnProjects() {
        for (Purchase[] pair : List.of(new Purchase[] {first, second}, new Purchase[] {second, first})) {
            Purchase own = pair[0];
            Account other = pair[1].creator();
            String path = ReviewJourney.path(own.projectId());
            assertThat(Stove.gateway.get(path, own.creator().headers()).status()).as("자기 프로젝트").isEqualTo(200);
            assertThat(Stove.gateway.get(path, other.headers()).status()).as("남의 프로젝트").isEqualTo(403);
            Response list = Stove.gateway.get("/api/v1/studio/projects", own.creator().headers());
            assertThat(list.status()).as(list.toString()).isEqualTo(200);
            JsonNode items = list.data().path("items");
            assertThat(items.size()).as("프로젝트 목록 격리 — %s", list).isEqualTo(1);
            assertThat(items.get(0).path("id").asLong()).isEqualTo(own.projectId());
            // Settlement는 지금 매출을 기록하지 않는다(결정 settlement-consumption-off). 남의 판매자를 질의해도
            // 자기 범위로만 답하는지는 매출이 다시 쌓일 때(플랫폼 T41) 단언을 더한다. 여기서는 문이 열리는지만 본다.
            Response sales = Stove.gateway.get("/api/v1/settlements/me/sales?sellerSubject=" + other.subject(),
                    own.creator().headers());
            assertThat(sales.status()).as("판매자 자기 매출 — %s", sales).isEqualTo(200);
            assertThat(sales.data().path("content").size()).as("소비를 끈 Settlement의 매출").isZero();
        }
    }

    @Test
    void rolesOpenOnlyTheirOwnDoors() {
        for (Account operator : List.of(reviewer, admin)) {
            assertThat(Stove.gateway.get("/api/v1/reviews", operator.headers()).status()).isEqualTo(200);
            assertThat(Stove.gateway.post("/api/v1/reviews/999999/approve", null, operator.headers()).status())
                    .as("없는 심사 요청까지 닿는다").isEqualTo(404);
        }
        for (Account outsider : List.of(first.buyer(), first.creator())) {
            assertThat(Stove.gateway.get("/api/v1/reviews", outsider.headers()).status()).isEqualTo(403);
            assertThat(Stove.gateway.get("/api/v1/admin/refunds", outsider.headers()).status()).isEqualTo(403);
        }
        assertThat(Stove.gateway.get("/api/v1/admin/refunds", reviewer.headers()).status())
                .as("심사자는 운영자가 아니다").isEqualTo(403);
        assertThat(Stove.gateway.get("/api/v1/admin/refunds", admin.headers()).status()).isEqualTo(200);
        assertThat(Stove.gateway.get("/api/v1/settlements/sales", admin.headers()).status()).isEqualTo(200);
        assertThat(Stove.gateway.get("/api/v1/settlements/sales", first.creator().headers()).status()).isEqualTo(403);
        assertThat(Stove.gateway.get("/api/v1/settlements/me/sales", first.buyer().headers()).status())
                .as("회원은 판매자가 아니다").isEqualTo(403);
        assertThat(Stove.gateway.get("/api/v1/orders").status()).as("로그인 없이").isEqualTo(401);
    }

    private static Purchase purchase(String title) throws Exception {
        Account creator = account(true);
        Project project = project(creator);
        page(project.id(), creator, title, 10000);
        approveAndPublish(project.id(), creator);
        String code = data(project.id(), creator).path("productCode").asText();
        Awaitility.await().atMost(Duration.ofSeconds(35)).untilAsserted(() ->
                assertThat(Stove.gateway.get("/api/v1/storefront/products/" + code).status()).isEqualTo(200));
        JsonNode product = Stove.gateway.get("/api/v1/products/by-code/" + code).data();

        Account buyer = account(false);
        Response ordered = Stove.gateway.post("/api/v1/orders", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "productCode", code,
                "expectedContentVersion", product.path("contentVersion").asLong(),
                "expectedAmount", product.path("price").asLong()), buyer.headers());
        assertThat(ordered.status()).as(ordered.toString()).isEqualTo(200);
        String orderNo = ordered.data().path("orderNo").asText();
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(Stove.gateway.get("/api/v1/orders/" + orderNo, buyer.headers()).data().path("status").asText())
                        .isEqualTo("PAID"));
        registeredLibrary(buyer, code);
        return new Purchase(creator, project.id(), buyer, code, orderNo);
    }
}
