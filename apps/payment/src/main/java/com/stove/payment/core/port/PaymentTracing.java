package com.stove.payment.core.port;

import com.stove.payment.core.domain.PaymentTraceContext;

public interface PaymentTracing {
    void run(PaymentTraceContext parent, String paymentId, Runnable work);
}
