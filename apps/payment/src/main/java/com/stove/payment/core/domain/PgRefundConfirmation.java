package com.stove.payment.core.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Verified full cancellation of a purchase, with no claim about who initiated it. */
public record PgRefundConfirmation(PgRefundLookupRequest purchase, List<RefundCancellation> cancellations, Instant cancelledAt) {
    public PgRefundConfirmation {
        if (purchase == null || cancellations == null || cancellations.isEmpty() || cancellations.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Full cancellation evidence is required");
        if (!PaymentValues.microsecond(cancelledAt) || cancelledAt.isBefore(purchase.paidAt()))
            throw new IllegalArgumentException("Invalid full cancellation time");
        Set<String> ids = new HashSet<>();
        long total = 0;
        for (RefundCancellation cancellation : cancellations) {
            Instant individualTime = cancellation.getCancelledAt();
            if (!ids.add(cancellation.getCancellationId()) || (individualTime != null
                    && (individualTime.isBefore(purchase.paidAt()) || individualTime.isAfter(cancelledAt))))
                throw new IllegalArgumentException("Contradictory cancellation evidence");
            total = Math.addExact(total, cancellation.getAmount());
        }
        if (total != purchase.amount()) throw new IllegalArgumentException("Cancellation is not for the full purchase");
        cancellations = cancellations.stream().sorted(Comparator.comparing(RefundCancellation::getCancellationId)).toList();
    }

    public PgRefundConfirmation(PgRefundLookupRequest purchase, List<RefundCancellation> cancellations) {
        this(purchase, cancellations, cancellations == null ? null : cancellations.stream().filter(Objects::nonNull)
                .map(RefundCancellation::getCancelledAt).filter(Objects::nonNull).max(Instant::compareTo).orElse(null));
    }
}
