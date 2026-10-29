package com.stove.payment.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name = "failed_payment_attempt")
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FailedPaymentAttempt {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, updatable = false, length = 36) private String attemptId;
    @Column(nullable = false, updatable = false, length = 64) private String paymentId;
    @Column(nullable = false, unique = true, updatable = false, length = 100) private String supplierAttemptId;
    @Column(nullable = false, updatable = false, length = 36) private String orderNo;
    @Column(nullable = false, updatable = false) private long amount;
    @Column(nullable = false, updatable = false, length = 3) private String currency;
    @Column(nullable = false, updatable = false) private Instant requestedAt;
    @Column(nullable = false, updatable = false) private Instant failedAt;
    @Column(nullable = false, updatable = false) private Instant observedAt;

    public static FailedPaymentAttempt record(Payment payment, PgFailedPaymentAttempt input, Instant at) {
        if (payment == null) throw new IllegalArgumentException("A payment is required");
        payment.requireFailedAttempt(input, at);
        FailedPaymentAttempt record = new FailedPaymentAttempt();
        record.attemptId = UUID.randomUUID().toString(); record.paymentId = payment.getPaymentId();
        record.supplierAttemptId = input.supplierAttemptId(); record.orderNo = payment.getOrderNo();
        record.amount = payment.getAmount(); record.currency = payment.getCurrency();
        record.requestedAt = input.requestedAt(); record.failedAt = input.failedAt(); record.observedAt = at;
        return record;
    }

    public void requireSame(PgFailedPaymentAttempt input) {
        if (input == null || !new PgChargeRequest(paymentId, orderNo, amount, currency).equals(input.purchase())
                || !supplierAttemptId.equals(input.supplierAttemptId()) || !requestedAt.equals(input.requestedAt()) || !failedAt.equals(input.failedAt()))
            throw new BusinessException(ErrorCode.CONFLICT, "이미 저장한 결제 시도의 실패 근거가 다릅니다.");
    }
}
