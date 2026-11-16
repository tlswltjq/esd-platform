package com.stove.payment.core.domain;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only proof for the operator failure closure (decision 128): the verified original purchase is PAID with no cancelled
 * amount, and every cancellation the PG lists is a full-amount FAILED one (none requested, none successful).
 */
public record PgRefundUncancelled(PgRefundLookupRequest purchase, List<RefundFailedCancellation> failedCancellations, String snapshotHash) {
    public PgRefundUncancelled {
        if (purchase == null || failedCancellations == null || failedCancellations.stream().anyMatch(Objects::isNull)
                || snapshotHash == null || !snapshotHash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("A verified uncancelled snapshot is required");
        Set<String> ids = new HashSet<>();
        for (RefundFailedCancellation row : failedCancellations) {
            // The same bounds a failed receipt has, so that the next attempt's preflight can expect this row.
            if (!ids.add(row.getCancellationId()) || row.getRequestedAt().isBefore(purchase.paidAt())
                    || row.getTaxFreeAmount() > purchase.amount() || row.getVatAmount() > purchase.amount() - row.getTaxFreeAmount())
                throw new IllegalArgumentException("Contradictory failed cancellation evidence");
            RefundPointAmounts.requireOriginalLimit(row.getEasyPayDiscountAmount(), purchase.paymentAmounts());
        }
        failedCancellations = failedCancellations.stream().sorted(Comparator.comparing(RefundFailedCancellation::getCancellationId)).toList();
    }
}
