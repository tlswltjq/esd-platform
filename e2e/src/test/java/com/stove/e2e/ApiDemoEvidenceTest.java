package com.stove.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.e2e.E2eClient.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** One sanitized record joins the real artifact, release, payments and month closing of this run. */
@Tag("api-demo")
@Order(10)
@DisplayName("전체 API 데모 — 실행별 검증 증거")
class ApiDemoEvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void writesVerifiedJourneyIdentifiersAndAmounts() throws Exception {
        String report = System.getProperty("stove.e2e.report", "");
        assertThat(report).as("-Dstove.e2e.report 에 실행별 결과 경로를 지정한다").isNotBlank();
        assertThat(Journey.isDownloadVerified()).as("서명 URL의 실제 ZIP SHA-256 검증").isTrue();

        Response product = Stove.gateway.get("/api/v1/products/by-code/" + Journey.PRODUCT_CODE);
        assertThat(product.status()).as("%s", product).isEqualTo(200);
        assertThat(product.data().path("productId").asLong()).isEqualTo(Journey.productId());
        long buildId = product.data().path("buildId").asLong();
        long releaseId = product.data().path("releaseId").asLong();
        assertThat(buildId).isPositive();
        assertThat(releaseId).isPositive();

        Response refundedOrder = Stove.gateway.get("/api/v1/orders/" + Journey.orderNo(),
                Journey.asMember(Journey.MEMBER));
        assertThat(refundedOrder.data().path("status").asText()).isEqualTo("CANCELED");
        Response library = Stove.gateway.get("/api/v1/library", Journey.asMember(Journey.MEMBER));
        assertThat(library.status()).isEqualTo(200);
        assertThat(library.itemWhere("orderNo", Journey.orderNo()).isMissingNode()).isTrue();

        ObjectNode seller = promotion("SELLER", Journey.sellerPromotion());
        ObjectNode platform = promotion("PLATFORM", Journey.platformPromotion());
        String sellerId = seller.path("sellerId").asText();
        String month = platform.path("settlementMonth").asText();
        Response closing = Stove.gateway.get("/api/v1/settlements/closings?month=" + month,
                Journey.asAdmin());
        assertThat(closing.status()).as("%s", closing).isEqualTo(200);
        JsonNode sellerClosing = closing.itemWhere("sellerId", sellerId);
        assertThat(sellerClosing.isMissingNode()).isFalse();
        assertThat(sellerClosing.path("taxInvoiceStatus").asText()).isEqualTo("SIMULATED");
        Response reconciliation = Stove.gateway.get("/api/v1/settlements/reconciliation?month=" + month,
                Journey.asAdmin());
        assertThat(reconciliation.status()).as("%s", reconciliation).isEqualTo(200);
        JsonNode sellerReconciliation = reconciliation.itemWhere("sellerId", sellerId);
        assertThat(sellerReconciliation.path("balanced").asBoolean()).isTrue();
        assertThat(sellerReconciliation.path("adjustmentCount").asInt()).isGreaterThanOrEqualTo(1);
        Response csv = Stove.gateway.get("/api/v1/settlements/export.csv?month=" + month,
                Journey.asAdmin());
        assertThat(csv.status()).as("%s", csv).isEqualTo(200);
        assertThat(csv.body().asText()).contains(Journey.platformPromotion().orderNo());
        if (seller.path("settlementMonth").asText().equals(month)) {
            assertThat(csv.body().asText()).contains(Journey.sellerPromotion().orderNo());
        } else {
            Response sellerMonthCsv = Stove.gateway.get("/api/v1/settlements/export.csv?month="
                    + seller.path("settlementMonth").asText(), Journey.asAdmin());
            assertThat(sellerMonthCsv.status()).isEqualTo(200);
            assertThat(sellerMonthCsv.body().asText()).contains(Journey.sellerPromotion().orderNo());
        }

        ObjectNode evidence = JSON.createObjectNode();
        evidence.put("runId", Journey.STAMP);
        evidence.put("verifiedAt", Instant.now().toString());
        evidence.put("productCode", Journey.PRODUCT_CODE);
        evidence.put("gameId", Journey.gameId());
        evidence.put("productId", Journey.productId());
        evidence.put("buildId", buildId);
        evidence.put("releaseId", releaseId);
        evidence.put("artifactSha256", Journey.buildChecksum());
        evidence.put("downloadedObjectMatchesSha256", true);
        evidence.put("refundedOrderNo", Journey.orderNo());
        evidence.put("settlementMonth", month);
        evidence.set("sellerPromotion", seller);
        evidence.set("platformPromotion", platform);
        evidence.set("closing", sellerClosing.deepCopy());
        evidence.set("reconciliation", sellerReconciliation.deepCopy());
        evidence.put("csvContainsBothPromotionOrders", true);

        Path path = Path.of(report).toAbsolutePath().normalize();
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence) + "\n");
        System.out.println("API demo evidence: " + path);
    }

    private static ObjectNode promotion(String bearer, Journey.PromotionEvidence expected) {
        Response ledger = Stove.gateway.get("/api/v1/settlements/orders/" + expected.orderNo(),
                Journey.asAdmin());
        assertThat(ledger.status()).as("%s", ledger).isEqualTo(200);
        JsonNode sale = ledger.itemWhere("recordType", "SALE");
        JsonNode refund = ledger.itemWhere("recordType", "REFUND");
        assertThat(sale.isMissingNode()).isFalse();
        assertThat(refund.isMissingNode()).isFalse();
        assertThat(sale.path("promotionId").asLong()).isEqualTo(expected.promotionId());
        assertThat(sale.path("discountBearer").asText()).isEqualTo(bearer);
        for (String amount : new String[]{"paidAmount", "grossAmount", "feeAmount", "netAmount",
                "discountAmount", "platformExpense"}) {
            assertThat(sale.path(amount).asLong() + refund.path(amount).asLong())
                    .as("%s %s 역분개", expected.orderNo(), amount).isZero();
        }
        ObjectNode value = JSON.createObjectNode();
        value.put("promotionId", expected.promotionId());
        value.put("orderNo", expected.orderNo());
        value.put("sellerId", sale.path("sellerId").asLong());
        value.put("discountBearer", bearer);
        value.put("settlementMonth", sale.path("settlementMonth").asText());
        value.put("customerPaid", sale.path("paidAmount").asLong());
        value.put("settlementBasis", sale.path("grossAmount").asLong());
        value.put("discountAmount", sale.path("discountAmount").asLong());
        value.put("platformExpense", sale.path("platformExpense").asLong());
        value.put("fee", sale.path("feeAmount").asLong());
        value.put("sellerPayout", sale.path("netAmount").asLong());
        value.put("refunded", true);
        return value;
    }
}
