package com.stove.e2e;

import static com.stove.e2e.DownloadPurchaseFlowTest.approveAndPublish;
import static com.stove.e2e.ReviewJourney.account;
import static com.stove.e2e.ReviewJourney.data;
import static com.stove.e2e.ReviewJourney.page;
import static com.stove.e2e.ReviewJourney.project;
import static com.stove.e2e.ReviewJourney.registeredLibrary;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.stove.e2e.E2eClient.Response;
import com.stove.e2e.ReviewJourney.Account;
import com.stove.e2e.ReviewJourney.Project;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;

/** 실제 API로 만드는 구매 한 건: 창작자가 출시하고, 구매자가 주문·결제하고 보유권을 등록한다. */
final class PurchaseJourney {

    /** 판매자 한 명이 낸 상품 하나와 그것을 산 구매자 한 명. */
    record Purchase(Account creator, long projectId, Account buyer, String productCode, String orderNo) {}

    private PurchaseJourney() {
    }

    static Purchase purchase(String title) throws Exception {
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
