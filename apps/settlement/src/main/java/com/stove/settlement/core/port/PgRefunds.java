package com.stove.settlement.core.port;

import com.stove.settlement.core.domain.ConfirmedRefund;
import com.stove.settlement.core.domain.PgPayment;
import com.stove.settlement.core.domain.PgRefund;

public interface PgRefunds {
    PgRefund require(PgPayment original, ConfirmedRefund confirmation);
}
