package com.stove.payment.core.domain;

/** Read-only proof: this cancellation FAILED and the verified original purchase is wholly unrefunded, with no active cancellations. */
public record PgRefundFailureConfirmation(PgRefundCancellationReceipt failedReceipt, String snapshotHash) {
    public PgRefundFailureConfirmation {
        if (failedReceipt == null || failedReceipt.state() != PgRefundCancellationReceipt.State.FAILED
                || snapshotHash == null || !snapshotHash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("A failed attempt and verified unrefunded snapshot are required");
    }
}
