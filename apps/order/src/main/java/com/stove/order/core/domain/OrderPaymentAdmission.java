package com.stove.order.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "order_payment_admission")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderPaymentAdmission {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, updatable = false, length = 36) private String admissionId;
    @Column(nullable = false, updatable = false, length = 36) private String orderNo;
    @Column(nullable = false, updatable = false, length = 64) private String requestEventId;
    @Column(nullable = false, updatable = false, length = 64) private String paymentId;
    @Column(nullable = false, updatable = false, length = 100) private String buyerSubject;
    @Column(nullable = false, updatable = false) private long amount;
    @Column(nullable = false, updatable = false, length = 3) private String currency;
    @Column(nullable = false, updatable = false) private Instant admittedAt;
    @Column(nullable = false, updatable = false) private Instant expiresAt;

    @Column(length = 100) private String failedSupplierAttemptId;
    private Instant failedAt;
    private Instant closedAt;
    @Column(length = 20) private String closedOrderStatus;

    public void requireFailure(PaymentAdmissionFailureInput input) {
        if (!admissionId.equals(input.admissionId()) || !orderNo.equals(input.orderNo()) || !paymentId.equals(input.paymentId()))
            throw new BusinessException(ErrorCode.CONFLICT,"Failure belongs to another admission");
        if (closedAt != null && (!failedSupplierAttemptId.equals(input.supplierAttemptId()) || !failedAt.equals(input.failedAt())))
            throw new BusinessException(ErrorCode.CONFLICT,"Original admission failure evidence changed");
    }
    public void closeFailure(PaymentAdmissionFailureInput input,Order order) {
        requireFailure(input);
        if (closedAt != null) return;
        if (!(order.getStatus()==OrderStatus.PENDING || order.getStatus()==OrderStatus.EXPIRED))
            throw new IllegalStateException("The failed processing must be released first");
        failedSupplierAttemptId=input.supplierAttemptId(); failedAt=input.failedAt();
        closedAt=order.getUpdatedAt(); closedOrderStatus=order.getStatus().name();
    }

    public static OrderPaymentAdmission record(Order order, PaymentAdmissionInput input) {
        if (order == null || input == null || order.getStatus() != OrderStatus.PAYMENT_PROCESSING || !order.getPaymentId().equals(input.paymentId())
                || !order.getOrderNo().equals(input.orderNo()) || !order.getRequestEventId().equals(input.requestEventId())
                || !order.getBuyerSubject().equals(input.buyerSubject()) || order.getAmount() != input.amount()
                || !order.getCurrency().equals(input.currency()) || !order.getUpdatedAt().isBefore(order.getExpiresAt()))
            throw new IllegalArgumentException("확정된 주문의 승인 진행 근거가 필요합니다.");
        OrderPaymentAdmission receipt = new OrderPaymentAdmission();
        receipt.admissionId = input.admissionId(); receipt.orderNo = input.orderNo();
        receipt.requestEventId = input.requestEventId(); receipt.paymentId = input.paymentId();
        receipt.buyerSubject = input.buyerSubject(); receipt.amount = input.amount(); receipt.currency = input.currency();
        receipt.admittedAt = order.getUpdatedAt(); receipt.expiresAt = order.getExpiresAt();
        return receipt;
    }

    public void requireSameInput(PaymentAdmissionInput input) {
        if (!new PaymentAdmissionInput(admissionId, orderNo, requestEventId, paymentId, buyerSubject, amount, currency).equals(input))
            throw new BusinessException(ErrorCode.CONFLICT, "동일 승인 진행 ID의 주문 또는 결제 내용이 다릅니다.");
    }
}
