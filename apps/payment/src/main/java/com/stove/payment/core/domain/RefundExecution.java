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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Durable receipt of an Order-authorized execution; receiving it does not call the supplier. */
@Entity @Table(name = "refund_execution")
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundExecution {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, updatable = false, length = 36)
    private String attemptId;
    @Column(nullable = false, updatable = false, length = 36)
    private String refundId;
    @Column(nullable = false, unique = true, updatable = false, length = 64)
    private String requestEventId;
    @Column(nullable = false, updatable = false)
    private Instant requestOccurredAt;
    @Column(nullable = false, updatable = false, length = 36)
    private String orderNo;
    @Column(nullable = false, updatable = false, length = 64)
    private String paymentId;
    @Column(nullable = false, updatable = false, length = 100)
    private String buyerSubject;
    @Column(nullable = false, updatable = false)
    private long amount;
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;
    @Column(nullable = false, updatable = false, length = 64)
    private String licenseSuspensionEventId;
    @Column(nullable = false, updatable = false)
    private Instant receivedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private RefundExecutionStatus status;

    public static RefundExecution receive(Payment payment, RefundExecutionRequest request, Instant receivedAt) {
        if (payment == null || !PaymentValues.microsecond(receivedAt)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "결제 근거와 환불 실행 접수 시각이 필요합니다.");
        }
        payment.requireRefundRequest(request);
        RefundExecution execution = new RefundExecution();
        execution.attemptId = request.attemptId(); execution.refundId = request.refundId();
        execution.requestEventId = request.eventId(); execution.requestOccurredAt = request.occurredAt();
        execution.orderNo = request.orderNo(); execution.paymentId = request.paymentId();
        execution.buyerSubject = request.buyerSubject(); execution.amount = request.amount();
        execution.currency = request.currency(); execution.licenseSuspensionEventId = request.licenseSuspensionEventId();
        execution.receivedAt = receivedAt; execution.status = RefundExecutionStatus.PENDING;
        return execution;
    }

    public RefundExecutionRequest request() {
        return new RefundExecutionRequest(requestEventId, requestOccurredAt, refundId, attemptId, orderNo,
                paymentId, buyerSubject, amount, currency, licenseSuspensionEventId);
    }

    public void requireSameRequest(RefundExecutionRequest request) {
        if (!request().equals(request)) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 접수한 환불 실행의 근거가 다릅니다.");
        }
    }
}
