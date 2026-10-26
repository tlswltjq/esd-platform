package com.stove.payment.core.port;

import com.stove.payment.core.domain.PaymentTraceContext;
import java.time.Instant;

public interface RefundExecutionTraceContexts {
    void save(String attemptId, PaymentTraceContext context, Instant receivedAt);
    PaymentTraceContext find(String attemptId);
}
