package com.stove.payment.adapter.pg;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.payment.core.domain.PgRefundConfirmation;
import com.stove.payment.core.domain.PgRefundCancellationReceipt;
import com.stove.payment.core.domain.PgRefundFailureConfirmation;
import com.stove.payment.core.domain.RefundLookupResult;
import com.stove.payment.core.domain.PgRefundLookupRequest;
import com.stove.payment.core.domain.PgUnavailable;
import com.stove.payment.core.domain.RefundCancellation;
import com.stove.payment.core.port.RefundPaymentLookup;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class PortOneRefundPaymentLookup implements RefundPaymentLookup {
    private final RestClient client;
    private final String storeId;
    private final String channelKey;
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public PortOneRefundPaymentLookup(RestClient client, String storeId, String channelKey) {
        this.client = client; this.storeId = storeId; this.channelKey = channelKey;
    }

    @Override public Optional<PgRefundConfirmation> find(PgRefundLookupRequest purchase) {
        return Optional.ofNullable(inspect(purchase, null).confirmation());
    }

    @Override public RefundLookupResult inspect(PgRefundLookupRequest purchase, PgRefundCancellationReceipt receipt) {
        try {
            String json = client.get().uri(uri -> uri.path("/payments/{id}").queryParam("storeId", storeId)
                    .build(purchase.paymentId())).exchange((outgoing, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) throw new PgUnavailable("Refund lookup unavailable");
                        return response.bodyTo(String.class);
                    });
            if (json == null) throw new IllegalArgumentException("Missing cancellation evidence");
            JsonNode body = mapper.readTree(json);
            if (body == null || !body.isObject()) throw new IllegalArgumentException("Missing cancellation object");
            if (!"CANCELLED".equals(text(body, "status"))) return inspectFailure(purchase, receipt, body, json);
            JsonNode channel = body.path("channel");
            JsonNode amount = body.path("amount");
            JsonNode custom = mapper.readTree(text(body, "customData"));
            if (!purchase.paymentId().equals(text(body, "id")) || !storeId.equals(text(body, "storeId"))
                    || !"V2".equals(text(body, "version")) || !purchase.currency().equals(text(body, "currency"))
                    || !"TEST".equals(text(channel, "type")) || !channelKey.equals(text(channel, "key"))
                    || !"TOSSPAY_V2".equals(text(channel, "pgProvider"))
                    || number(amount, "total") != purchase.amount() || number(amount, "paid") != purchase.amount()
                    || number(amount, "discount") != 0 || number(amount, "cancelled") != purchase.amount()
                    || custom == null || !purchase.orderNo().equals(text(custom, "orderNo"))
                    || !purchase.transactionId().equals(text(body, "pgTxId")) || !purchase.paidAt().equals(time(body, "paidAt")))
                throw new IllegalArgumentException("Cancellation does not match the paid purchase");
            JsonNode rows = body.path("cancellations");
            if (!rows.isArray() || rows.isEmpty()) throw new IllegalArgumentException("Missing successful cancellations");
            Set<String> ids = new HashSet<>();
            List<RefundCancellation> successes = new ArrayList<>();
            for (JsonNode row : rows) {
                if (!ids.add(text(row, "id"))) throw new IllegalArgumentException("Duplicate cancellation identity");
                String status = text(row, "status");
                if ("SUCCEEDED".equals(status)) successes.add(new RefundCancellation(text(row, "id"),
                        number(row, "totalAmount"), row.path("cancelledAt").isMissingNode() || row.path("cancelledAt").isNull()
                                ? null : time(row, "cancelledAt")));
                else if (!"FAILED".equals(status)) throw new IllegalArgumentException("Unresolved cancellation evidence");
            }
            PgRefundConfirmation confirmation = new PgRefundConfirmation(purchase, successes, time(body, "cancelledAt"));
            return new RefundLookupResult(confirmation, null);
        } catch (RestClientException unavailable) {
            throw new PgUnavailable("PortOne refund lookup failed");
        } catch (JsonProcessingException | DateTimeException | ArithmeticException invalid) {
            throw new IllegalArgumentException("Invalid PortOne cancellation evidence", invalid);
        }
    }

    private RefundLookupResult inspectFailure(PgRefundLookupRequest purchase, PgRefundCancellationReceipt receipt, JsonNode body, String json)
            throws JsonProcessingException {
        if (receipt == null || !"PAID".equals(text(body, "status"))) return new RefundLookupResult(null, null);
        JsonNode channel = body.path("channel"); JsonNode amount = body.path("amount");
        JsonNode custom = mapper.readTree(text(body, "customData"));
        if (!purchase.equals(receipt.purchase()) || !purchase.paymentId().equals(text(body, "id")) || !storeId.equals(text(body, "storeId"))
                || !"V2".equals(text(body, "version")) || !purchase.currency().equals(text(body, "currency"))
                || !"TEST".equals(text(channel, "type")) || !channelKey.equals(text(channel, "key")) || !"TOSSPAY_V2".equals(text(channel, "pgProvider"))
                || number(amount, "total") != purchase.amount() || number(amount, "paid") != purchase.amount() || number(amount, "discount") != 0
                || custom == null || !purchase.orderNo().equals(text(custom, "orderNo"))
                || !purchase.transactionId().equals(text(body, "pgTxId")) || !purchase.paidAt().equals(time(body, "paidAt")))
            throw new IllegalArgumentException("Failure lookup does not match the paid purchase");
        if (number(amount, "cancelled") != 0) return new RefundLookupResult(null, null);
        JsonNode rows = body.path("cancellations");
        if (!rows.isArray()) throw new IllegalArgumentException("Cancellation history is required for restoration");
        Set<String> ids = new HashSet<>();
        PgRefundCancellationReceipt found = null;
        for (JsonNode row : rows) {
            String id = text(row, "id");
            if (!ids.add(id)) throw new IllegalArgumentException("Duplicate cancellation identity");
            if (!"FAILED".equals(text(row, "status"))) return new RefundLookupResult(null, null);
            if (receipt.cancellationId().equals(id)) {
                if (number(row, "totalAmount") != purchase.amount()
                        || (row.has("easyPayDiscountAmount") && number(row, "easyPayDiscountAmount") != 0))
                    throw new IllegalArgumentException("Failed cancellation amount mismatch");
                found = new PgRefundCancellationReceipt(purchase, receipt.attemptId(), id, PgRefundCancellationReceipt.State.FAILED,
                        time(row, "requestedAt"), number(row, "taxFreeAmount"), number(row, "vatAmount"), text(row, "reason"));
                if (!found.equals(receipt.failed())) throw new IllegalArgumentException("Failed cancellation does not match our receipt");
            }
        }
        if (found == null) return new RefundLookupResult(null, null);
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
            return new RefundLookupResult(null, new PgRefundFailureConfirmation(found, hash));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    private static String text(JsonNode body, String name) {
        JsonNode value = body.path(name);
        if (!value.isTextual() || value.textValue().isBlank()) throw new IllegalArgumentException("Missing cancellation field");
        return value.textValue();
    }
    private static long number(JsonNode body, String name) {
        JsonNode value = body.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException("Invalid cancellation amount");
        return value.longValue();
    }
    private static Instant time(JsonNode body, String name) {
        return Instant.parse(text(body, name)).truncatedTo(ChronoUnit.MICROS);
    }
}
