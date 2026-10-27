package com.stove.payment.api.listener;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.payment.core.domain.PortOneWebhookNotice;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.DateTimeException;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

public class PortOneWebhookMapper {
    private static final Set<String> PAYMENT_TYPES = Set.of("Transaction.Ready", "Transaction.Paid", "Transaction.Failed",
            "Transaction.Cancelled", "Transaction.PartialCancelled", "Transaction.CancelPending", "Transaction.PayPending");
    private final ObjectMapper mapper;
    private final String expectedStoreId;

    public PortOneWebhookMapper(ObjectMapper mapper, String expectedStoreId) {
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.expectedStoreId = expectedStoreId;
    }

    public Optional<PortOneWebhookNotice> from(byte[] body, String messageId) {
        if (body == null || body.length == 0 || body.length > 65536) throw new IllegalArgumentException("Invalid webhook body");
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("Webhook object required");
            String type = text(root, "type");
            if (!PAYMENT_TYPES.contains(type)) return Optional.empty();
            JsonNode data = root.path("data");
            String store = text(data, "storeId");
            if (!expectedStoreId.equals(store)) throw new IllegalArgumentException("Webhook store mismatch");
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            return Optional.of(new PortOneWebhookNotice(messageId, hash, type, store, text(data, "paymentId"),
                    text(data, "transactionId"), Instant.parse(text(root, "timestamp")).truncatedTo(ChronoUnit.MICROS)));
        } catch (IOException | DateTimeException invalid) {
            throw new IllegalArgumentException("Invalid webhook JSON");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) throw new IllegalArgumentException("Missing webhook identifier");
        return value.textValue();
    }
}
