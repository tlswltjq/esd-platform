package com.stove.settlement.core.port;

import com.stove.settlement.core.domain.PgPayment;

public interface PgPayments { PgPayment require(String paymentId); }
