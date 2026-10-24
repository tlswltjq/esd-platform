package com.stove.payment.core.port;

import com.stove.payment.core.domain.PaymentTraceContext;
import java.time.Instant;

public interface PaymentTraceContexts {
    void save(String paymentId, PaymentTraceContext context, Instant receivedAt);

    PaymentTraceContext find(String paymentId);
}
