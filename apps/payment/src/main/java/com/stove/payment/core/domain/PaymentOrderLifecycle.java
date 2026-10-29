package com.stove.payment.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name="payment_order_lifecycle")
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class PaymentOrderLifecycle {
    @Id @Column(length=36) private String orderNo;
    @Column(nullable=false,length=64,updatable=false) private String requestEventId;
    @Column(nullable=false,length=100,updatable=false) private String buyerSubject;
    @Column(nullable=false,updatable=false) private long amount;
    @Column(nullable=false,length=3,updatable=false) private String currency;
    @Column(nullable=false,updatable=false) private Instant expiresAt;
    @Column(length=64,unique=true) private String expiryEventId;
    private Instant expiredAt;
    @Column(nullable=false) private boolean financialConflict;

    public static PaymentOrderLifecycle from(PaymentOrderRequest request) {
        PaymentOrderLifecycle value=new PaymentOrderLifecycle(); value.orderNo=request.orderNo(); value.requestEventId=request.eventId();
        value.buyerSubject=request.buyerSubject(); value.amount=request.amount(); value.currency=request.currency();
        value.expiresAt=request.occurredAt().plus(Duration.ofMinutes(30)); return value;
    }
    public void requireOriginal(PaymentOrderRequest request) {
        requireTerms(request.orderNo(),request.eventId(),request.buyerSubject(),request.amount(),request.currency(),request.occurredAt().plus(Duration.ofMinutes(30)));
    }
    public boolean recordExpiry(OrderExpiryInput input) {
        requireTerms(input.orderNo(),input.requestEventId(),input.buyerSubject(),input.amount(),input.currency(),input.expiresAt());
        if(expired()) {
            if(!expiryEventId.equals(input.eventId()) || !expiredAt.equals(input.occurredAt()))throw conflict();
            return false;
        }
        expiryEventId=input.eventId(); expiredAt=input.occurredAt(); return true;
    }
    private void requireTerms(String order,String request,String buyer,long amount,String currency,Instant expires) {
        if(!orderNo.equals(order) || !requestEventId.equals(request) || !buyerSubject.equals(buyer) || this.amount!=amount
                || !this.currency.equals(currency) || !expiresAt.equals(expires))throw conflict();
    }
    private static BusinessException conflict() { return new BusinessException(ErrorCode.CONFLICT,"Order expiry does not match its immutable purchase"); }
    public boolean expired() { return expiryEventId!=null; }
    public void retainFinancialConflict() { financialConflict=true; }
}
