package com.stove.payment.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.time.Instant;

public record OrderExpiryInput(String eventId,Instant occurredAt,String orderNo,String requestEventId,
        String buyerSubject,long amount,String currency,Instant expiresAt) {
    public OrderExpiryInput {
        requireIdentity(eventId,orderNo,requestEventId,buyerSubject);
        requireCharge(amount,currency);
        requireTimes(occurredAt,expiresAt);
    }
    private static void requireIdentity(String event,String order,String request,String buyer) {
        if(!PaymentValues.text(event,64) || !PaymentValues.uuid(order) || !PaymentValues.text(request,64) || !PaymentValues.text(buyer,100))throw invalid();
    }
    private static void requireCharge(long amount,String currency) { if(amount<=0 || !"KRW".equals(currency))throw invalid(); }
    private static void requireTimes(Instant occurred,Instant expires) {
        if(!PaymentValues.microsecond(occurred) || !PaymentValues.microsecond(expires) || occurred.isBefore(expires))throw invalid();
    }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST,"Invalid confirmed order expiry"); }
}
