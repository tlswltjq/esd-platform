package com.stove.settlement;

import static com.stove.settlement.SettlementFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.adapter.CatalogOwnerHttpClient;
import com.stove.settlement.adapter.SimulatedPgPaymentHttpClient;
import com.stove.settlement.adapter.SimulatedPgSettlementHttpClient;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class SettlementHttpContractTest {
    private HttpServer server;
    private final ObjectMapper json=new ObjectMapper();
    private final AtomicReference<String> response=new AtomicReference<String>();
    private RestClient client;
    private String paymentId;
    @BeforeEach void server() throws Exception {
        paymentId=id();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Pg-Api-Key")).isEqualTo("read-only-key");
            byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length);try(OutputStream out=exchange.getResponseBody()) { out.write(bytes); }
        });server.start();
        client=RestClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).defaultHeader("X-Pg-Api-Key","read-only-key").build();
    }
    @AfterEach void close() { server.stop(0); }
    ObjectNode payment() throws Exception {
        return (ObjectNode)json.readTree("""
                {"provider":"SIMULATED","paymentId":"PAYMENT","orderNo":"ORDER","amount":10000,"currency":"KRW",
                "providerTransactionId":"TRANSACTION","providerOccurredAt":"2026-10-01T00:00:00Z","decision":"APPROVED",
                "pgFeePolicyId":"fee-v1","pgFeeRateBps":300,"pgFeeAmount":300}
                """.replace("PAYMENT",paymentId).replace("ORDER",id()).replace("TRANSACTION",id()));
    }
    @Test void feeLookupRejectsCoercionUnknownFeesUnapprovedResultsAndBrokenIdentity() throws Exception {
        ObjectNode valid=payment();response.set(valid.toString());SimulatedPgPaymentHttpClient gateway=new SimulatedPgPaymentHttpClient(client);
        assertThat(gateway.require(paymentId).cost().getPgFeeAmount()).isEqualTo(300);
        for(String field:List.of("amount","pgFeeRateBps","pgFeeAmount")) {
            ObjectNode invalid=valid.deepCopy();invalid.put(field,"300");response.set(invalid.toString());assertUnavailable(() -> gateway.require(paymentId));
            invalid=valid.deepCopy();invalid.put(field,1.5);response.set(invalid.toString());assertUnavailable(() -> gateway.require(paymentId));
        }
        for(String field:List.of("pgFeePolicyId","pgFeeRateBps","pgFeeAmount","providerOccurredAt")) {
            ObjectNode invalid=valid.deepCopy();invalid.putNull(field);response.set(invalid.toString());assertUnavailable(() -> gateway.require(paymentId));
        }
        ObjectNode invalid=valid.deepCopy();invalid.put("decision","DECLINED");response.set(invalid.toString());assertUnavailable(() -> gateway.require(paymentId));
        invalid=valid.deepCopy();invalid.put("providerOccurredAt","bad-time");response.set(invalid.toString());assertUnavailable(() -> gateway.require(paymentId));
        response.set(valid.toString());assertUnavailable(() -> gateway.require(id()));
    }
    @Test void payoutTotalsAndItemDuplicationAreVerifiedBeforeReception() throws Exception {
        ObjectNode report=json.createObjectNode();String reportId=id();
        report.put("provider","SIMULATED").put("providerSettlementId",reportId).put("currency","KRW").put("cutoffAt","2026-10-02T00:00:00Z")
                .put("paidAt","2026-10-02T00:00:00Z").put("status","SIMULATED_PAID").put("paymentAmountTotal",10000)
                .put("pgFeeAmountTotal",300).put("payoutAmount",9700).put("recordCount",1);
        report.putArray("items").add(payment());response.set(report.toString());SimulatedPgSettlementHttpClient gateway=new SimulatedPgSettlementHttpClient(client);
        assertThat(gateway.require(reportId).payoutAmount()).isEqualTo(9700);
        ObjectNode bad=report.deepCopy();bad.put("payoutAmount",1);response.set(bad.toString());assertUnavailable(() -> gateway.require(reportId));
        bad=report.deepCopy();bad.withArray("items").add(bad.path("items").get(0));response.set(bad.toString());assertUnavailable(() -> gateway.require(reportId));
    }
    @Test void ownerLookupRequiresEnvelopeAndMatchingProductIdentity() {
        CatalogOwnerHttpClient gateway=new CatalogOwnerHttpClient(client);
        response.set("{\"success\":true,\"data\":{\"productId\":1,\"productCode\":\"game-1\",\"ownerSubject\":\"usr_seller\"}}");
        assertThat(gateway.require("game-1").sellerSubject()).isEqualTo("usr_seller");assertUnavailable(() -> gateway.require("game-2"));
        response.set("{\"productId\":1,\"productCode\":\"game-1\",\"ownerSubject\":\"usr_seller\"}");assertUnavailable(() -> gateway.require("game-1"));
    }
    private void assertUnavailable(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE));
    }
}
