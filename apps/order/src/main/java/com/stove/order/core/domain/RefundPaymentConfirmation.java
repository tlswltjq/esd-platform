package com.stove.order.core.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

/** Money confirmation is independent of the outcome of our execution attempt. */
public record RefundPaymentConfirmation(String eventId, Instant occurredAt, String refundId, String attemptId,
        String requestEventId, String orderNo, String paymentId, String buyerSubject, long amount, String currency,
        String source, String providerTransactionId, List<String> cancellationIds) {
    public RefundPaymentConfirmation {
        if (!text(eventId, 64) || !time(occurredAt) || !uuid(refundId) || !uuid(attemptId) || !text(requestEventId, 64)
                || !uuid(orderNo) || !text(paymentId, 64) || !text(buyerSubject, 100) || amount <= 0 || !"KRW".equals(currency)
                || !("PG_QUERY_UNATTRIBUTED".equals(source) || "PG_RESPONSE".equals(source)) || !text(providerTransactionId, 100) || cancellationIds == null
                || cancellationIds.isEmpty() || cancellationIds.stream().anyMatch(id -> !text(id, 100))
                || new HashSet<>(cancellationIds).size() != cancellationIds.size() || ("PG_RESPONSE".equals(source) && cancellationIds.size() != 1))
            throw new RefundCompletionRejected("전액 취소 확인의 근거가 올바르지 않습니다.");
        cancellationIds = cancellationIds.stream().sorted().toList();
    }

    public String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : List.of(eventId, occurredAt.toString(), refundId, attemptId, requestEventId,
                    orderNo, paymentId, buyerSubject, Long.toString(amount), currency, source, providerTransactionId)) update(digest, value);
            for (String id : cancellationIds) update(digest, id);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII)); digest.update(bytes);
    }
    private static boolean text(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
    private static boolean uuid(String value) { return value != null && value.matches(OrderValues.UUID_PATTERN); }
    private static boolean time(Instant value) { return value != null && value.getNano() % 1000 == 0; }
}
