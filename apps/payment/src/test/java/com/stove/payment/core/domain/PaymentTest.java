package com.stove.payment.core.domain;


import static com.stove.payment.PaymentFixtures.*;
import static org.assertj.core.api.Assertions.*;
import com.stove.common.web.error.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PaymentTest {
    @Test void requestIsFrozenIndependentlyOfLaterPrices() {
        PaymentOrderRequest r=request(); Payment p=Payment.receive(r);
        assertThat(p.orderRequest()).isEqualTo(r);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(p.getProvider()).isEqualTo("SIMULATED");
        assertThat(p.getNextAttemptAt()).isNotNull();
        p.requireSameRequest(r);
        for (PaymentOrderRequest other:new PaymentOrderRequest[]{
                new PaymentOrderRequest(id(),r.occurredAt(),r.orderNo(),r.buyerSubject(),1L,r.productCode(),r.title(),3,10000,0,10000,"KRW"),
                new PaymentOrderRequest(r.eventId(),r.occurredAt().plusSeconds(1),r.orderNo(),r.buyerSubject(),1L,r.productCode(),r.title(),3,10000,0,10000,"KRW"),
                new PaymentOrderRequest(r.eventId(),r.occurredAt(),r.orderNo(),r.buyerSubject(),1L,r.productCode(),"Changed",4,20000,0,20000,"KRW")})
            assertThatThrownBy(() -> p.requireSameRequest(other)).isInstanceOf(BusinessException.class);
    }
    @Test void preservesDiscountedCustomerChargeWithoutRecalculatingPolicyOrFees() {
        PaymentOrderRequest r=request();
        Payment p=Payment.receive(new PaymentOrderRequest(r.eventId(),r.occurredAt(),r.orderNo(),r.buyerSubject(),1L,r.productCode(),r.title(),3,10000,1000,9000,"KRW"));
        assertThat(p.chargeRequest().amount()).isEqualTo(9000);
        assertThat(p.getListAmount()).isEqualTo(10000);
        assertThat(p.getDiscountAmount()).isEqualTo(1000);
    }
    @Test void confirmedResultIsImmutableAndDuplicateCannotChangeEventOrTimestamp() {
        Payment p=Payment.receive(request()); PgResult result=approved(p); String eventId=id(); Instant time=Payment.now();
        assertThat(p.confirm(result,eventId,time)).isTrue();
        Instant updated=p.getUpdatedAt();
        assertThat(p.confirm(result,id(),Payment.now())).isFalse();
        assertThat(p.getResultEventId()).isEqualTo(eventId); assertThat(p.getResultOccurredAt()).isEqualTo(time);
        assertThat(p.getUpdatedAt()).isEqualTo(updated); assertThat(p.supplierResult()).isEqualTo(result);
        assertThat(p.getNextAttemptAt()).isNull(); assertThat(p.getLastErrorCode()).isNull();
        assertThatThrownBy(() -> p.confirm(approved(p),id(),Payment.now())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> p.confirm(result(p,PgResult.Decision.DECLINED),id(),Payment.now())).isInstanceOf(BusinessException.class);
    }
    @Test void finalDeclinePreservesReasonAndCannotBeReopenedByAnError() {
        Payment p=Payment.receive(request()); PgResult r=result(p,PgResult.Decision.DECLINED);
        p.confirm(r,id(),Payment.now()); Instant updated=p.getUpdatedAt();
        p.defer(Payment.now().plusSeconds(5),"PG_UNAVAILABLE");
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.getFailureReasonCode()).isEqualTo("DECLINED"); assertThat(p.getFailureReason()).isEqualTo(r.reason());
        assertThat(p.getNextAttemptAt()).isNull(); assertThat(p.getUpdatedAt()).isEqualTo(updated);
    }
    @Test void unknownOutcomeOnlySchedulesAnotherCheck() {
        Payment p=Payment.receive(request()); Instant next=Payment.now().plusSeconds(5);
        p.defer(next,"PG_UNAVAILABLE");
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING); assertThat(p.getNextAttemptAt()).isEqualTo(next);
        assertThat(p.getResultEventId()).isNull(); assertThat(p.supplierResult()).isNull();
        assertThat(p.getFailureReasonCode()).isNull();
    }
    @Test void wrongSupplierIdentityAndNetAmountCannotApprovePayment() {
        Payment p=Payment.receive(request()); PgResult r=approved(p);
        for (PgResult wrong:new PgResult[]{
                new PgResult("OTHER",r.paymentId(),r.orderNo(),r.amount(),r.currency(),r.transactionId(),r.occurredAt(),r.decision(),null,null),
                new PgResult("SIMULATED",id(),r.orderNo(),r.amount(),r.currency(),r.transactionId(),r.occurredAt(),r.decision(),null,null),
                new PgResult("SIMULATED",r.paymentId(),id(),r.amount(),r.currency(),r.transactionId(),r.occurredAt(),r.decision(),null,null),
                new PgResult("SIMULATED",r.paymentId(),r.orderNo(),9800,r.currency(),r.transactionId(),r.occurredAt(),r.decision(),null,null)})
            assertThatThrownBy(() -> p.confirm(wrong,id(),Payment.now())).isInstanceOf(IllegalArgumentException.class);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING); assertThat(p.getResultEventId()).isNull();
    }
    @Test void resultAndRequestValuesRejectIncompleteOrInconsistentFacts() {
        PaymentOrderRequest r=request(); Payment p=Payment.receive(r); PgResult result=approved(p);
        assertThatThrownBy(() -> new PaymentOrderRequest(r.eventId(),r.occurredAt(),r.orderNo(),r.buyerSubject(),1L,r.productCode(),r.title(),3,10000,2000,10000,"KRW"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new PgChargeRequest(p.getPaymentId(),p.getOrderNo(),0,"KRW")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PgChargeRequest(p.getPaymentId(),p.getOrderNo(),10000,"USD")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PgResult("SIMULATED",p.getPaymentId(),p.getOrderNo(),10000,"KRW",id(),Payment.now(),PgResult.Decision.DECLINED,null,null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.confirm(result,null,Payment.now())).isInstanceOf(IllegalArgumentException.class);
    }

}
