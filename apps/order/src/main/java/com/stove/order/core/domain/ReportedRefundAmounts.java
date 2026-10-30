package com.stove.order.core.domain;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A validated snapshot; a cancellation total is distinct from a payment-method refund. */
public record ReportedRefundAmounts(long orderAmount, ProviderPaymentAmounts paymentAmounts,
        List<ReportedRefundCancellation> cancellations, Instant providerCancelledAt) {
    public ReportedRefundAmounts {
        if (orderAmount <= 0 || cancellations == null || cancellations.isEmpty() || cancellations.stream().anyMatch(Objects::isNull)
                || (providerCancelledAt != null && providerCancelledAt.getNano() % 1000 != 0))
            throw new RefundCompletionRejected("반환 금액의 확인 근거가 필요합니다.");
        if (paymentAmounts != null) paymentAmounts.requireTotal(orderAmount);
        Set<String> ids = new HashSet<>();
        long total = 0;
        long reportedPoints = 0;
        try {
            for (ReportedRefundCancellation cancellation : cancellations) {
                if (!ids.add(cancellation.cancellationId())) throw new RefundCompletionRejected("취소 금액의 식별자가 중복됐습니다.");
                total = Math.addExact(total, cancellation.totalAmount());
                if (cancellation.pointAmount() != null) reportedPoints = Math.addExact(reportedPoints, cancellation.pointAmount());
            }
        } catch (ArithmeticException overflow) { throw new RefundCompletionRejected("취소 금액의 합계 범위를 초과했습니다.", overflow); }
        if (total != orderAmount || (paymentAmounts == null && reportedPoints != 0)
                || (paymentAmounts != null && reportedPoints > paymentAmounts.discountAmount()))
            throw new RefundCompletionRejected("취소 총액·보고 포인트가 원승인과 다릅니다.");
        cancellations = cancellations.stream().sorted(Comparator.comparing(ReportedRefundCancellation::cancellationId)).toList();
    }

    public Long confirmedPointAmount() {
        if (cancellations.stream().anyMatch(cancellation -> cancellation.pointAmount() == null)) return null;
        long total = 0;
        for (ReportedRefundCancellation cancellation : cancellations) total = Math.addExact(total, cancellation.pointAmount());
        return total;
    }

    public List<String> cancellationIds() {
        return cancellations.stream().map(ReportedRefundCancellation::cancellationId).toList();
    }

    public String fingerprint() { return fingerprint(false); }
    public String cancellationIdsFingerprint() { return fingerprint(true); }

    private String fingerprint(boolean idsOnly) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (!idsOnly) {
                update(digest, Long.toString(orderAmount));
                update(digest, paymentAmounts == null ? "unknown" : Long.toString(paymentAmounts.paidAmount()));
                update(digest, paymentAmounts == null ? "unknown" : Long.toString(paymentAmounts.discountAmount()));
                update(digest, providerCancelledAt == null ? "unknown" : providerCancelledAt.toString());
            }
            for (ReportedRefundCancellation cancellation : cancellations) {
                update(digest, cancellation.cancellationId());
                if (!idsOnly) {
                    update(digest, Long.toString(cancellation.totalAmount()));
                    update(digest, cancellation.pointAmount() == null ? "unknown" : cancellation.pointAmount().toString());
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII)); digest.update(bytes);
    }
}
