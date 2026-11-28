package com.stove.e2e;

import static com.stove.e2e.DownloadPurchaseFlowTest.approveAndPublish;
import static com.stove.e2e.DownloadPurchaseFlowTest.verifyZip;
import static com.stove.e2e.ReviewJourney.ADMIN_EMAIL;
import static com.stove.e2e.ReviewJourney.ADMIN_PASSWORD;
import static com.stove.e2e.ReviewJourney.account;
import static com.stove.e2e.ReviewJourney.data;
import static com.stove.e2e.ReviewJourney.login;
import static com.stove.e2e.ReviewJourney.page;
import static com.stove.e2e.ReviewJourney.project;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.stove.e2e.ReviewJourney.Account;
import com.stove.e2e.ReviewJourney.Project;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 구매→지급→다운로드 여정(결정 201의 기준선). 결제는 프로세스 안 승인 어댑터가 확정한다(결정 in-process-pg-approval).
 * Settlement는 떠 있지만 소비하지 않는다(결정 settlement-consumption-off).
 */
@Tag("purchase")
class PurchaseDownloadFlowTest {
    private static JsonNode buy(JsonNode product, Account buyer) {
        E2eClient.Response response = Stove.gateway.post("/api/v1/orders", Map.of("requestId", UUID.randomUUID().toString(),
                "productCode", product.path("productCode").asText(), "expectedContentVersion", product.path("contentVersion").asLong(),
                "expectedAmount", product.path("price").asLong()), buyer.headers());
        assertThat(response.status()).isEqualTo(200);
        String order = response.data().path("orderNo").asText();
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(Stove.gateway.get("/api/v1/orders/" + order, buyer.headers()).data().path("status").asText()).isEqualTo("PAID"));
        return response.data();
    }

    @Test void purchasesAndDownloads() throws Exception {
        List<JsonNode> products = new ArrayList<JsonNode>();
        List<byte[]> zips = new ArrayList<byte[]>();
        for (int i = 0; i < 2; i++) {
            Account creator = account(true);
            Project project = project(creator);
            page(project.id(), creator, "Purchase product " + i, 10000);
            approveAndPublish(project.id(), creator);
            String code = data(project.id(), creator).path("productCode").asText();
            Awaitility.await().atMost(Duration.ofSeconds(35)).untilAsserted(() ->
                    assertThat(Stove.gateway.get("/api/v1/storefront/products/" + code).status()).isEqualTo(200));
            products.add(Stove.gateway.get("/api/v1/products/by-code/" + code).data());
            zips.add(project.zip());
        }
        for (int index : new int[] {0, 1, 0}) {
            Account buyer = account(false);
            JsonNode product = products.get(index);
            JsonNode order = buy(product, buyer);
            JsonNode payment = Stove.gateway.get("/api/v1/payments/" + order.path("orderNo").asText(), buyer.headers()).data();
            assertThat(payment.path("status").asText()).isEqualTo("PAID");
            assertThat(payment.path("provider").asText()).isEqualTo("SIMULATED");
            String code = product.path("productCode").asText();
            ReviewJourney.registeredLibrary(buyer, code);
            E2eClient.Response ticket = Stove.gateway.get("/api/v1/downloads/" + code + "/ticket", buyer.headers());
            assertThat(ticket.status()).isEqualTo(200);
            verifyZip(ticket.data(), zips.get(index));
        }
    }

    @Test void settlementServesReadsButRecordsNoSale() throws Exception {
        Account admin = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        E2eClient.Response sales = Stove.gateway.get("/api/v1/settlements/sales?size=100", admin.headers());
        assertThat(sales.status()).isEqualTo(200);
        assertThat(sales.data().path("totalElements").asInt()).isZero();
        assertThat(Stove.gateway.get("/api/v1/settlements/sales").status()).isEqualTo(401);
        assertThat(Stove.gateway.post("/api/v1/settlements/pg-receipts",
                Map.of("providerSettlementId", UUID.randomUUID().toString()), admin.headers()).status()).isEqualTo(503);
    }
}
