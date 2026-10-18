package com.stove.payment.core.service;

import com.stove.common.kafka.contract.payload.OrderPaymentCompletedEvent;
import com.stove.common.kafka.contract.payload.OrderPaymentFailedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.port.PgGateway;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
public class ResolvePayment {
    private final PaymentRepository payments;
    private final PgGateway gateway;
    private final OutboxRecorder outbox;
    private final EntityManager entityManager;
    private final int batchSize;
    private final Duration retryDelay;
    private final TransactionTemplate transaction;

    public ResolvePayment(PaymentRepository payments, PgGateway gateway, OutboxRecorder outbox,
            EntityManager entityManager, PlatformTransactionManager transactions, int batchSize, Duration retryDelay) {
        this.payments = payments;
        this.gateway = gateway;
        this.outbox = outbox;
        this.entityManager = entityManager;
        this.batchSize = batchSize;
        this.retryDelay = retryDelay;
        transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public void execute() {
        List<String> due = payments.due(PaymentStatus.PENDING, Payment.now(), PageRequest.of(0, batchSize));
        for (String paymentId : due) {
            try {
                execute(paymentId);
            } catch (RuntimeException failure) {
                log.warn("결제 처리 재개 실패 paymentId={}", paymentId, failure);
            }
        }
    }

    /** No Payment transaction is held while calling the supplier. */
    public void execute(String paymentId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("PG 호출은 Payment 트랜잭션 밖에서 실행해야 합니다.");
        }
        Payment payment = payments.findByPaymentId(paymentId).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.PENDING) return;

        PgResult result;
        try {
            result = gateway.charge(payment.chargeRequest());
            if (result == null) throw new IllegalArgumentException("Missing PG result");
            result.requireRequest(payment.chargeRequest());
        } catch (IllegalArgumentException | BusinessException invalid) {
            defer(paymentId, "PG_INVALID_RESULT", invalid);
            return;
        } catch (RuntimeException unavailable) {
            defer(paymentId, "PG_UNAVAILABLE", unavailable);
            return;
        }

        try {
            transaction.executeWithoutResult(tx -> confirm(paymentId, result));
        } catch (IllegalArgumentException | BusinessException invalid) {
            defer(paymentId, "PG_INVALID_RESULT", invalid);
        } catch (RuntimeException unconfirmed) {
            defer(paymentId, "RESULT_UNCONFIRMED", unconfirmed);
        }
    }

    private void confirm(String paymentId, PgResult result) {
        Payment current = payments.lockByPaymentId(paymentId).orElseThrow();
        if (!current.confirm(result, UUID.randomUUID().toString(), Payment.now())) return;

        if (current.getStatus() == PaymentStatus.PAID) {
            outbox.record("Payment", current.getOrderNo(), new OrderPaymentCompletedEvent(
                    current.getResultEventId(), current.getResultOccurredAt(), current.getOrderNo(), current.getRequestEventId(),
                    current.getPaymentId(), current.getBuyerSubject(), current.getAmount(), current.getCurrency()));
        } else {
            outbox.record("Payment", current.getOrderNo(), new OrderPaymentFailedEvent(
                    current.getResultEventId(), current.getResultOccurredAt(), current.getOrderNo(), current.getRequestEventId(),
                    current.getPaymentId(), current.getBuyerSubject(), current.getAmount(), current.getCurrency(),
                    current.getFailureReasonCode(), current.getFailureReason()));
        }
        entityManager.flush();
    }

    private void defer(String paymentId, String code, RuntimeException failure) {
        log.warn("결제 결과 미확정 paymentId={} code={}", paymentId, code, failure);
        try {
            transaction.executeWithoutResult(tx -> payments.lockByPaymentId(paymentId).ifPresent(
                    payment -> payment.defer(Payment.now().plus(retryDelay), code)));
        } catch (RuntimeException unavailable) {
            log.warn("미확정 결제 재시도 예약 실패 paymentId={}", paymentId, unavailable);
        }
    }
}
