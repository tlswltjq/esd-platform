package com.stove.payment.core.port;

import com.stove.payment.core.domain.*;

public interface PgGateway {
    /** Every retry must keep paymentId. An unconfirmed call throws, rather than returning a decline. */
    PgResult charge(PgChargeRequest request);
}
