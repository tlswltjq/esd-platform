package com.stove.payment.adapter.trace;

import com.stove.payment.core.domain.PaymentTraceContext;
import com.stove.payment.core.port.PaymentTracing;

public class DisabledPaymentTracing implements PaymentTracing {
    @Override
    public void run(PaymentTraceContext parent, String paymentId, Runnable work) {
        work.run();
    }
}
