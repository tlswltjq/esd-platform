package com.stove.payment.core.port;

import com.stove.payment.core.domain.PgRefundConfirmation;
import com.stove.payment.core.domain.PgRefundLookupRequest;
import java.util.Optional;
import com.stove.payment.core.domain.RefundLookupResult;
import com.stove.payment.core.domain.PgRefundCancellationReceipt;
import com.stove.payment.core.domain.PgRefundUncancelled;

/** Read-only full-cancellation verification. Empty means that completion is not established. */
public interface RefundPaymentLookup {
    Optional<PgRefundConfirmation> find(PgRefundLookupRequest purchase);
    default RefundLookupResult inspect(PgRefundLookupRequest purchase, PgRefundCancellationReceipt receipt) {
        return new RefundLookupResult(find(purchase).orElse(null), null);
    }
    /**
     * Decision 128: proof that the original payment is PAID with no cancelled amount and only full-amount FAILED
     * cancellations. Empty means the proof is not established (cancelled, partly cancelled, requested or successful
     * cancellation, or a supplier without this read); the failure closure then refuses.
     */
    default Optional<PgRefundUncancelled> inspectUncancelled(PgRefundLookupRequest purchase) {
        return Optional.empty();
    }
}
