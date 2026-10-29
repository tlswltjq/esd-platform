package com.stove.payment.adapter.pg;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.payment.core.domain.ApprovalObservation;
import com.stove.payment.core.domain.ApprovalPostRejected;
import com.stove.payment.core.domain.ApprovalPurchase;
import com.stove.payment.core.domain.ApprovalToken;
import com.stove.payment.core.domain.PgChargeRequest;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.PgUnavailable;
import com.stove.payment.core.port.PgPaymentApprovalGateway;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class PortOnePaymentApprovalGateway implements PgPaymentApprovalGateway {
    private final RestClient client;
    private final String storeId;
    private final String channelKey;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    public PortOnePaymentApprovalGateway(RestClient client, String storeId, String channelKey, Clock clock) {
        this.client = client; this.storeId = storeId; this.channelKey = channelKey; this.clock = clock;
    }
    @Override public String prepareBody(ApprovalPurchase purchase, ApprovalToken token) {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("storeId", storeId); body.put("paymentToken", token.value()); body.put("txId", purchase.supplierAttemptId());
        body.put("currency", purchase.purchase().currency()); body.put("totalAmount", purchase.purchase().amount()); body.put("isTest", true);
        try { return mapper.writeValueAsString(body); }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException("Approval payload could not be prepared"); }
    }
    @Override public ApprovalObservation inspect(ApprovalPurchase requested) {
        JsonNode current = get("/payments/{id}", requested.purchase().paymentId());
        requirePurchase(current, requested.purchase(), false);
        String currentAttempt = text(current, "transactionId");
        String status = text(current, "status");
        if ("PAID".equals(status)) {
            if (!currentAttempt.equals(requested.supplierAttemptId())) throw new IllegalArgumentException("Paid transaction is not the admitted attempt");
            requireAmounts(current, requested.purchase().amount(), true);
            PgResult paid = new PgResult("PORTONE_TEST", requested.purchase().paymentId(), requested.purchase().orderNo(),
                    requested.purchase().amount(), requested.purchase().currency(), text(current,"pgTxId"), time(current,"paidAt"),
                    PgResult.Decision.APPROVED, null, null, null);
            return new ApprovalObservation(ApprovalObservation.State.PAID, paid, null);
        }
        if (currentAttempt.equals(requested.supplierAttemptId())) return unpaid(current, requested.purchase());
        JsonNode history = get("/payments/{id}/transactions", requested.purchase().paymentId());
        if (!history.path("items").isArray()) throw new IllegalArgumentException("Missing approval transaction history");
        ApprovalObservation found = null;
        for (JsonNode item : history.path("items")) {
            if (!requested.supplierAttemptId().equals(text(item,"id"))) continue;
            requirePurchase(item, requested.purchase(), true);
            ApprovalObservation next = unpaid(item, requested.purchase());
            if (found != null && !found.equals(next)) throw new IllegalArgumentException("Conflicting approval attempt history");
            found = next;
        }
        if (found == null) throw new PgUnavailable("Authenticated attempt is not yet visible");
        return found;
    }
    private ApprovalObservation unpaid(JsonNode item, PgChargeRequest purchase) {
        requireAmounts(item, purchase.amount(), false);
        return switch (text(item,"status")) {
            case "READY" -> new ApprovalObservation(ApprovalObservation.State.READY, null, null);
            case "PAY_PENDING", "PENDING" -> new ApprovalObservation(ApprovalObservation.State.PENDING, null, null);
            case "FAILED" -> {
                if (!item.path("failure").isObject()) throw new IllegalArgumentException("Missing failed-attempt evidence");
                yield new ApprovalObservation(ApprovalObservation.State.FAILED, null, time(item,"failedAt"));
            }
            default -> throw new IllegalArgumentException("Unexpected approval transaction state");
        };
    }
    @Override public void submit(String approvalId, ApprovalPurchase purchase, String body, Instant preparedAt, Instant leaseUntil) {
        Instant now = clock.instant();
        if (preparedAt == null || leaseUntil == null || now.isBefore(preparedAt)
                || !now.isBefore(preparedAt.plus(Duration.ofHours(3))) || !now.isBefore(leaseUntil)) return;
        try {
            client.post().uri("/payments/{id}/confirm", purchase.purchase().paymentId())
                    .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "\"" + approvalId + "\"")
                    .body(body).exchange((outgoing,response) -> {
                        if (response.getStatusCode().is2xxSuccessful()) return null;
                        int status = response.getStatusCode().value();
                        if (status == 400 || status == 403) throw new ApprovalPostRejected();
                        if (status == 409) {
                            JsonNode error = response.bodyTo(JsonNode.class);
                            String type = error == null ? "" : error.path("type").asText();
                            if ("ALREADY_PAID".equals(type) || "IDEMPOTENCY_OUTSTANDING_REQUEST".equals(type)) return null;
                            throw new ApprovalPostRejected();
                        }
                        throw new PgUnavailable("Approval response remains unconfirmed");
                    });
        } catch (RestClientException unavailable) { throw new PgUnavailable("Approval HTTP outcome remains unconfirmed"); }
    }
    private JsonNode get(String path, String paymentId) {
        try {
            JsonNode result = client.get().uri(uri -> uri.path(path).queryParam("storeId",storeId).build(paymentId))
                    .exchange((request,response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) throw new PgUnavailable("Approval lookup unavailable");
                        return response.bodyTo(JsonNode.class);
                    });
            if (result == null || !result.isObject()) throw new IllegalArgumentException("Missing approval lookup body");
            return result;
        } catch (RestClientException unavailable) { throw new PgUnavailable("Approval lookup HTTP unavailable"); }
    }
    private void requirePurchase(JsonNode item, PgChargeRequest purchase, boolean transaction) {
        JsonNode custom;
        try { custom = mapper.readTree(text(item,"customData")); }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException("Invalid approval purchase data"); }
        JsonNode channel = item.path("channel");
        if (!purchase.paymentId().equals(text(item,transaction ? "paymentId" : "id")) || !storeId.equals(text(item,"storeId"))
                || !"V2".equals(text(item,"version")) || !purchase.currency().equals(text(item,"currency"))
                || !"TEST".equals(text(channel,"type")) || !channelKey.equals(text(channel,"key"))
                || !"TOSSPAY_V2".equals(text(channel,"pgProvider")) || custom == null || !custom.isObject()
                || !purchase.orderNo().equals(text(custom,"orderNo")) || number(item.path("amount"),"total") != purchase.amount())
            throw new IllegalArgumentException("Approval purchase does not match the server snapshot");
    }
    private static void requireAmounts(JsonNode item, long amount, boolean paid) {
        JsonNode values = item.path("amount");
        if (number(values,"total") != amount || number(values,"paid") != (paid ? amount : 0)
                || number(values,"cancelled") != 0 || number(values,"discount") != 0)
            throw new IllegalArgumentException("Approval amounts do not match the purchase");
    }
    private static String text(JsonNode item, String name) {
        if (!item.path(name).isTextual() || item.path(name).asText().isBlank()) throw new IllegalArgumentException("Missing approval evidence field");
        return item.path(name).asText();
    }
    private static long number(JsonNode item, String name) {
        if (!item.path(name).isIntegralNumber() || !item.path(name).canConvertToLong()) throw new IllegalArgumentException("Invalid approval amount");
        return item.path(name).longValue();
    }
    private static Instant time(JsonNode item, String name) {
        try { return Instant.parse(text(item,name)).truncatedTo(ChronoUnit.MICROS); }
        catch (DateTimeException invalid) { throw new IllegalArgumentException("Invalid approval evidence time"); }
    }
}
