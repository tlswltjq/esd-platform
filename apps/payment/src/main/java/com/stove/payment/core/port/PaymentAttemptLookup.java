package com.stove.payment.core.port;

import com.stove.payment.core.domain.PgChargeRequest;
import com.stove.payment.core.domain.PgFailedPaymentAttempt;
import java.util.List;

public interface PaymentAttemptLookup {
    /** Read-only supplier history. A FAILED attempt never authorizes a final payment decline. */
    List<PgFailedPaymentAttempt> findFailed(PgChargeRequest purchase);
}
