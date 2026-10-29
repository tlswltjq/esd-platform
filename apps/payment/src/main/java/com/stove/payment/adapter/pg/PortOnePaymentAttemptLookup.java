package com.stove.payment.adapter.pg;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.payment.core.domain.PgChargeRequest;
import com.stove.payment.core.domain.PgFailedPaymentAttempt;
import com.stove.payment.core.domain.PgUnavailable;
import com.stove.payment.core.port.PaymentAttemptLookup;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** PortOne's transaction IDs identify attempts; they are distinct from pgTxId and paymentId. */
public class PortOnePaymentAttemptLookup implements PaymentAttemptLookup {
    private final RestClient client;
    private final String storeId;
    private final String channelKey;
    private final ObjectMapper mapper = new ObjectMapper();

    public PortOnePaymentAttemptLookup(RestClient client, String storeId, String channelKey) {
        this.client = client; this.storeId = storeId; this.channelKey = channelKey;
    }

    @Override public List<PgFailedPaymentAttempt> findFailed(PgChargeRequest purchase) {
        try {
            JsonNode body = client.get().uri(uri -> uri.path("/payments/{id}/transactions").queryParam("storeId", storeId).build(purchase.paymentId()))
                    .exchange((request, reply) -> {
                        if (!reply.getStatusCode().is2xxSuccessful()) throw new PgUnavailable("PortOne attempt history unavailable");
                        return reply.bodyTo(JsonNode.class);
                    });
            if (body == null || !body.path("items").isArray()) throw new IllegalArgumentException("Missing PortOne attempt history");
            Map<String, PgFailedPaymentAttempt> verified = new LinkedHashMap<>();
            for (JsonNode item : body.path("items")) {
                if (!purchase.paymentId().equals(text(item, "paymentId")) || !storeId.equals(text(item, "storeId")) || !"V2".equals(text(item, "version")))
                    throw new IllegalArgumentException("Attempt history belongs to another payment");
                if (!"FAILED".equals(text(item, "status"))) continue;
                JsonNode channel = item.path("channel"); JsonNode amount = item.path("amount");
                JsonNode custom = mapper.readTree(text(item, "customData"));
                if (!"TEST".equals(text(channel, "type")) || !channelKey.equals(text(channel, "key")) || !"TOSSPAY_V2".equals(text(channel, "pgProvider"))
                        || !purchase.currency().equals(text(item, "currency")) || number(amount, "total") != purchase.amount()
                        || number(amount, "paid") != 0 || number(amount, "discount") != 0 || number(amount, "cancelled") != 0
                        || custom == null || !purchase.orderNo().equals(text(custom, "orderNo")) || !item.path("failure").isObject())
                    throw new IllegalArgumentException("Failed attempt does not match the original checkout");
                PgFailedPaymentAttempt fact = new PgFailedPaymentAttempt(purchase, text(item, "id"), time(item, "requestedAt"), time(item, "failedAt"));
                PgFailedPaymentAttempt previous = verified.putIfAbsent(fact.supplierAttemptId(), fact);
                if (previous != null && !previous.equals(fact)) throw new IllegalArgumentException("Conflicting supplier attempt identity");
            }
            return List.copyOf(verified.values());
        } catch (RestClientException unavailable) {
            throw new PgUnavailable("PortOne attempt history HTTP request failed");
        } catch (JsonProcessingException | DateTimeException invalid) {
            throw new IllegalArgumentException("Invalid PortOne failed-attempt evidence");
        }
    }

    private static Instant time(JsonNode item, String name) { return Instant.parse(text(item, name)).truncatedTo(ChronoUnit.MICROS); }
    private static String text(JsonNode body, String field) {
        JsonNode value = body.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) throw new IllegalArgumentException("Missing PortOne attempt field");
        return value.textValue();
    }
    private static long number(JsonNode body, String field) {
        JsonNode value = body.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException("Invalid PortOne attempt amount");
        return value.longValue();
    }
}
