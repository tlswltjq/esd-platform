package com.stove.payment.core.port;

import com.stove.payment.core.domain.PgRefundConfirmation;
import com.stove.payment.core.domain.PgRefundLookupRequest;
import java.util.Optional;
import com.stove.payment.core.domain.RefundLookupResult;
import com.stove.payment.core.domain.PgRefundCancellationReceipt;

/** Read-only full-cancellation verification. Empty means that completion is not established. */
public interface RefundPaymentLookup {
    Optional<PgRefundConfirmation> find(PgRefundLookupRequest purchase);
    default RefundLookupResult inspect(PgRefundLookupRequest purchase, PgRefundCancellationReceipt receipt) {
        return new RefundLookupResult(find(purchase).orElse(null), null);
    }
}
