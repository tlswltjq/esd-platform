package com.stove.payment.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name = "purchase_payment") @Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,unique=true,length=64,updatable=false) private String paymentId;
    @Column(nullable=false,unique=true,length=36,updatable=false) private String orderNo;
    @Column(nullable=false,unique=true,length=64,updatable=false) private String requestEventId;
    @Column(nullable=false,updatable=false) private Instant orderOccurredAt;
    @Column(nullable=false,length=100,updatable=false) private String buyerSubject;
    @Column(nullable=false,updatable=false) private Long productId;
    @Column(nullable=false,length=50,updatable=false) private String productCode;
    @Column(nullable=false,length=200,updatable=false) private String title;
    @Column(nullable=false,updatable=false) private long contentVersion;
    @Column(nullable=false,updatable=false) private long listAmount;
    @Column(nullable=false,updatable=false) private long discountAmount;
    @Column(nullable=false,updatable=false) private long amount;
    @Column(nullable=false,length=3,updatable=false) private String currency;
    @Column(nullable=false,length=30,updatable=false) private String provider;
    @Column(length=100) private String pgFeePolicyId;
    private Integer pgFeeRateBps;
    private Long pgFeeAmount;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private PaymentStatus status;
    @Column(length=100) private String providerTransactionId;
    private Instant providerOccurredAt;
    @Column(unique=true,length=64) private String resultEventId;
    private Instant resultOccurredAt;
    @Column(length=100) private String failureReasonCode;
    @Column(length=500) private String failureReason;
    private Instant nextAttemptAt;
    @Column(length=100) private String lastErrorCode;
    @Column(nullable=false,updatable=false) private Instant createdAt;
    @Column(nullable=false) private Instant updatedAt;

    public static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }

    public static Payment receive(PaymentOrderRequest request, String provider) {
        if (request == null || !PaymentValues.text(provider,30)) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        Payment p = new Payment();
        p.paymentId=UUID.randomUUID().toString(); p.orderNo=request.orderNo();
        p.requestEventId=request.eventId(); p.orderOccurredAt=request.occurredAt(); p.buyerSubject=request.buyerSubject();
        p.productId=request.productId(); p.productCode=request.productCode(); p.title=request.title();
        p.contentVersion=request.contentVersion(); p.listAmount=request.listAmount(); p.discountAmount=request.discountAmount();
        p.amount=request.amount(); p.currency=request.currency(); p.provider=provider;
        p.status=PaymentStatus.PENDING; p.createdAt=now(); p.updatedAt=p.createdAt; p.nextAttemptAt=p.createdAt;
        return p;
    }

    public PaymentOrderRequest orderRequest() {
        return new PaymentOrderRequest(requestEventId,orderOccurredAt,orderNo,buyerSubject,productId,productCode,
                title,contentVersion,listAmount,discountAmount,amount,currency);
    }

    public void requireSameRequest(PaymentOrderRequest request) {
        if (!orderRequest().equals(request))
            throw new BusinessException(ErrorCode.CONFLICT,"동일 주문의 결제 요청 내용이 다릅니다.");
    }

    public PgChargeRequest chargeRequest() { return new PgChargeRequest(paymentId,orderNo,amount,currency); }

    public PgResult supplierResult() {
        return status==PaymentStatus.PENDING ? null : new PgResult(provider,paymentId,orderNo,amount,currency,
                providerTransactionId,providerOccurredAt,status==PaymentStatus.PAID ? PgResult.Decision.APPROVED : PgResult.Decision.DECLINED,
                failureReasonCode,failureReason,pgFeePolicyId == null ? null : new PgFee(pgFeePolicyId,pgFeeRateBps,pgFeeAmount));
    }

    /** Final supplier decisions are immutable, including their original transaction and timestamp. */
    public boolean confirm(PgResult result, String eventId, Instant occurredAt) {
        if (result==null) throw new IllegalArgumentException("Confirmed PG result is required");
        result.requireRequest(chargeRequest());
        if (!provider.equals(result.provider())) throw new IllegalArgumentException("PG provider does not match");
        if (status!=PaymentStatus.PENDING) {
            if (!supplierResult().equals(result)) throw new BusinessException(ErrorCode.CONFLICT,"확정된 결제 결과가 다릅니다.");
            return false;
        }
        if (!PaymentValues.text(eventId,64) || !PaymentValues.microsecond(occurredAt))
            throw new IllegalArgumentException("Result event identity is required");
        providerTransactionId=result.transactionId(); providerOccurredAt=result.occurredAt();
        if (result.fee() != null) {
            pgFeePolicyId=result.fee().policyId(); pgFeeRateBps=result.fee().rateBps(); pgFeeAmount=result.fee().amount();
        }
        status=result.decision()==PgResult.Decision.APPROVED ? PaymentStatus.PAID : PaymentStatus.FAILED;
        failureReasonCode=result.reasonCode(); failureReason=result.reason();
        resultEventId=eventId; resultOccurredAt=occurredAt;
        nextAttemptAt=null; lastErrorCode=null; updatedAt=now();
        return true;
    }

    public void defer(Instant until, String errorCode) {
        if (status!=PaymentStatus.PENDING) return;
        if (!PaymentValues.microsecond(until) || !PaymentValues.text(errorCode,100))
            throw new IllegalArgumentException("Invalid pending retry");
        nextAttemptAt=until; lastErrorCode=errorCode; updatedAt=now();
    }
}
