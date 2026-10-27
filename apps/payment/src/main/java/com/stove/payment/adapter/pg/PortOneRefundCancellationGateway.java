package com.stove.payment.adapter.pg;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.payment.core.domain.PgRefundLookupRequest;
import com.stove.payment.core.domain.PgUnavailable;
import com.stove.payment.core.domain.PgRefundCancellationSuccess;
import com.stove.payment.core.domain.PgRefundCancellationReceipt;
import com.stove.payment.core.domain.RefundCancellation;
import com.stove.payment.core.domain.RefundCancellationDispatch;
import com.stove.payment.core.domain.RefundCancellationPermission;
import com.stove.payment.core.domain.RefundExecutionRequest;
import com.stove.payment.core.port.RefundCancellationGateway;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class PortOneRefundCancellationGateway implements RefundCancellationGateway {
    private final RestClient client;
    private final String storeId;
    private final String channelKey;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public PortOneRefundCancellationGateway(RestClient client, String storeId, String channelKey, Clock clock) {
        this.client = client; this.storeId = storeId; this.channelKey = channelKey; this.clock = clock;
    }

    @Override public String prepareBody(RefundExecutionRequest request) {
        if (request == null) throw new IllegalArgumentException("Original refund request is required");
        CancellationBody body = new CancellationBody(storeId, request.amount(), request.amount(), "ESD refund " + request.refundId());
        try { return mapper.writeValueAsString(body); }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException("Cannot encode cancellation request", invalid); }
    }

    @Override public RefundCancellationDispatch send(RefundCancellationPermission permission, PgRefundLookupRequest purchase, String body) {
        permission.requireCancellationAllowedAt(clock.instant());
        requireBody(permission, purchase, body);
        try {
            String json = client.get().uri(uri -> uri.path("/payments/{id}").queryParam("storeId", storeId)
                    .build(purchase.paymentId())).exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) throw new PgUnavailable("Cancellation preflight unavailable");
                        return response.bodyTo(String.class);
                    });
            JsonNode payment = read(json);
            requirePurchase(purchase, payment);
            long cancelled = number(payment.path("amount"), "cancelled");
            if (cancelled < 0 || cancelled > purchase.amount()) throw new IllegalArgumentException("Invalid cancelled balance");
            if (cancelled > 0 && cancelled < purchase.amount()) return RefundCancellationDispatch.BALANCE_CHANGED;
            JsonNode cancellations = payment.path("cancellations");
            if (!cancellations.isMissingNode() && !cancellations.isArray()) throw new IllegalArgumentException("Invalid cancellation list");
            if (!"PAID".equals(text(payment, "status")) || cancelled != 0 || (cancellations.isArray() && !cancellations.isEmpty()))
                return RefundCancellationDispatch.NOT_READY;
            // A slow preflight cannot carry a once-valid permission beyond the three-hour guarantee.
            permission.requireCancellationAllowedAt(clock.instant());
            return client.post().uri("/payments/{id}/cancel", purchase.paymentId())
                    .header("Idempotency-Key", "\"" + permission.idempotencyKey() + "\"")
                    .contentType(MediaType.APPLICATION_JSON).body(body.getBytes(StandardCharsets.UTF_8))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().is2xxSuccessful())
                            return successResponse(permission, purchase, body, response.bodyTo(String.class));
                        if (response.getStatusCode().value() == 409) {
                            JsonNode error = read(response.bodyTo(String.class));
                            if ("CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN".equals(text(error, "type")))
                                return RefundCancellationDispatch.BALANCE_CHANGED;
                        }
                        throw new PgUnavailable("Cancellation outcome is unconfirmed");
                    });
        } catch (RestClientException unavailable) {
            throw new PgUnavailable("Cancellation HTTP outcome is unconfirmed");
        } catch (DateTimeException invalid) {
            throw new IllegalArgumentException("Invalid original payment time", invalid);
        }
    }

    private RefundCancellationDispatch successResponse(RefundCancellationPermission permission, PgRefundLookupRequest purchase,
            String requestBody, String responseBody) {
        try {
            JsonNode value = read(responseBody).path("cancellation");
            String status = text(value, "status");
            if (!("SUCCEEDED".equals(status) || "REQUESTED".equals(status) || "FAILED".equals(status))) return RefundCancellationDispatch.UNCONFIRMED;
            if (!text(read(requestBody), "reason").equals(text(value, "reason"))) return RefundCancellationDispatch.UNCONFIRMED;
            Instant requestedAt = Instant.parse(text(value, "requestedAt")).truncatedTo(ChronoUnit.MICROS);
            Instant cancelledAt = value.path("cancelledAt").isMissingNode() || value.path("cancelledAt").isNull() ? null
                    : Instant.parse(text(value, "cancelledAt")).truncatedTo(ChronoUnit.MICROS);
            Instant observedAt = clock.instant();
            if (requestedAt.isAfter(observedAt) || (cancelledAt != null && cancelledAt.isAfter(observedAt))
                    || (value.has("easyPayDiscountAmount") && number(value, "easyPayDiscountAmount") != 0))
                return RefundCancellationDispatch.UNCONFIRMED;
            if (!"SUCCEEDED".equals(status)) {
                if (number(value, "totalAmount") != purchase.amount()) return RefundCancellationDispatch.UNCONFIRMED;
                return RefundCancellationDispatch.received(new PgRefundCancellationReceipt(purchase, permission.idempotencyKey(),
                        text(value, "id"), PgRefundCancellationReceipt.State.valueOf(status), requestedAt,
                        number(value, "taxFreeAmount"), number(value, "vatAmount"), text(value, "reason")));
            }
            PgRefundCancellationSuccess success = new PgRefundCancellationSuccess(purchase, permission.idempotencyKey(),
                    new RefundCancellation(text(value, "id"), number(value, "totalAmount"), cancelledAt), requestedAt,
                    number(value, "taxFreeAmount"), number(value, "vatAmount"), text(value, "reason"));
            return RefundCancellationDispatch.succeeded(success);
        } catch (IllegalArgumentException | DateTimeException invalid) { return RefundCancellationDispatch.UNCONFIRMED; }
    }

    private void requireBody(RefundCancellationPermission permission, PgRefundLookupRequest purchase, String body) {
        RefundExecutionRequest request = permission.request();
        if (purchase == null || !request.paymentId().equals(purchase.paymentId()) || !request.orderNo().equals(purchase.orderNo())
                || request.amount() != purchase.amount() || !request.currency().equals(purchase.currency()))
            throw new IllegalArgumentException("Cancellation does not match the original request");
        JsonNode json = read(body);
        if (json.size() != 4 || !storeId.equals(text(json, "storeId")) || number(json, "amount") != request.amount()
                || number(json, "currentCancellableAmount") != request.amount() || !text(json, "reason").contains(request.refundId()))
            throw new IllegalArgumentException("Stored cancellation body is not for the original full amount");
    }

    private void requirePurchase(PgRefundLookupRequest purchase, JsonNode payment) {
        JsonNode channel = payment.path("channel"); JsonNode amount = payment.path("amount");
        if (!purchase.paymentId().equals(text(payment, "id")) || !storeId.equals(text(payment, "storeId"))
                || !"V2".equals(text(payment, "version")) || !purchase.currency().equals(text(payment, "currency"))
                || !"TEST".equals(text(channel, "type")) || !channelKey.equals(text(channel, "key"))
                || !"TOSSPAY_V2".equals(text(channel, "pgProvider")) || number(amount, "total") != purchase.amount()
                || number(amount, "paid") != purchase.amount() || number(amount, "discount") != 0
                || !purchase.transactionId().equals(text(payment, "pgTxId"))
                || !purchase.paidAt().equals(Instant.parse(text(payment, "paidAt")).truncatedTo(ChronoUnit.MICROS))
                || !purchase.orderNo().equals(text(read(text(payment, "customData")), "orderNo")))
            throw new IllegalArgumentException("Cancellation preflight does not match the paid purchase");
    }

    private JsonNode read(String body) {
        try {
            if (body == null) throw new IllegalArgumentException("Missing supplier object");
            JsonNode value = mapper.readTree(body);
            if (value == null || !value.isObject()) throw new IllegalArgumentException("Invalid supplier object");
            return value;
        } catch (JsonProcessingException invalid) { throw new IllegalArgumentException("Invalid supplier JSON", invalid); }
    }
    private static String text(JsonNode value, String name) {
        JsonNode field = value.path(name);
        if (!field.isTextual() || field.textValue().isBlank()) throw new IllegalArgumentException("Missing supplier field");
        return field.textValue();
    }
    private static long number(JsonNode value, String name) {
        JsonNode field = value.path(name);
        if (!field.isIntegralNumber() || !field.canConvertToLong()) throw new IllegalArgumentException("Invalid supplier amount");
        return field.longValue();
    }

    private record CancellationBody(String storeId, long amount, long currentCancellableAmount, String reason) { }
}
