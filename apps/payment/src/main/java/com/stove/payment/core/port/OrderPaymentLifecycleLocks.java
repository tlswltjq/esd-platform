package com.stove.payment.core.port;

import java.time.Instant;

public interface OrderPaymentLifecycleLocks {
    void ensure(String orderNo,String requestEventId,String buyerSubject,long amount,String currency,Instant expiresAt);
}
