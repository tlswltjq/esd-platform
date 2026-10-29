package com.stove.payment.core.service;

import com.stove.common.kafka.contract.payload.OrderPaymentCompletedEvent;
import com.stove.common.kafka.contract.payload.OrderPaymentFailedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.PgFailedPaymentAttempt;
import com.stove.payment.core.domain.FailedPaymentAttempt;
import com.stove.payment.core.domain.FailedPaymentAttemptRepository;
import com.stove.payment.core.port.PaymentAttemptLookup;
import com.stove.payment.core.port.PgGateway;
import com.stove.payment.core.port.PaymentTraceContexts;
import com.stove.payment.core.port.PaymentTracing;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
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
    private final PaymentTraceContexts traceContexts;
    private final PaymentTracing tracing;
    private final String provider;
    private final PaymentAttemptLookup attemptLookup;
    private final FailedPaymentAttemptRepository failedAttempts;

    public ResolvePayment(PaymentRepository payments, PgGateway gateway, OutboxRecorder outbox,
            EntityManager entityManager, PlatformTransactionManager transactions, int batchSize, Duration retryDelay,
            PaymentTraceContexts traceContexts, PaymentTracing tracing, String provider,
            PaymentAttemptLookup attemptLookup, FailedPaymentAttemptRepository failedAttempts) {
        this.payments = payments;
        this.gateway = gateway;
        this.outbox = outbox;
        this.entityManager = entityManager;
        this.batchSize = batchSize;
        this.retryDelay = retryDelay;
        this.traceContexts = traceContexts;
        this.tracing = tracing;
        this.provider = provider;
        this.attemptLookup = attemptLookup;
        this.failedAttempts = failedAttempts;
        transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public void execute() {
        List<String> due = payments.due(PaymentStatus.PENDING, provider, Payment.now(), PageRequest.of(0, batchSize));
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
        if (payment == null || !provider.equals(payment.getProvider()) || (payment.getStatus() != PaymentStatus.PENDING && payment.getNextFailureLookupAt() == null)) return;

        tracing.run(traceContexts.find(paymentId), paymentId, () -> process(payment));
    }

    private void process(Payment payment) {
        if (payment.getStatus() == PaymentStatus.PENDING) processFinancialResult(payment);
        if ("PORTONE_TEST".equals(provider)) collectFailureHistory(payment.getPaymentId());
    }

    private void processFinancialResult(Payment payment) {
        String paymentId = payment.getPaymentId();
        PgResult result;
        try {
            result = gateway.charge(payment.chargeRequest());
            if (result == null) throw new IllegalArgumentException("Missing PG result");
            result.requireRequest(payment.chargeRequest());
        } catch (IllegalArgumentException | BusinessException invalid) {
            defer(paymentId, "PG_INVALID_RESULT", invalid); return;
        } catch (RuntimeException unavailable) {
            defer(paymentId, "PG_UNAVAILABLE", unavailable); return;
        }
        try {
            transaction.executeWithoutResult(tx -> {
                Payment current = payments.lockByPaymentId(paymentId).orElseThrow();
                if ("PORTONE_TEST".equals(provider) && current.getStatus() == PaymentStatus.PENDING) {
                    Instant at = Payment.now();
                    current.recordFailureLookup(false, at, at.plus(retryDelay));
                }
                confirm(current, result);
                entityManager.flush();
            });
        } catch (IllegalArgumentException | BusinessException invalid) {
            defer(paymentId, "PG_INVALID_RESULT", invalid);
        } catch (RuntimeException unconfirmed) {
            defer(paymentId, "RESULT_UNCONFIRMED", unconfirmed);
        }
    }

    /** Approval and its Outbox have already committed before this optional supplier lookup. */
    private void collectFailureHistory(String paymentId) {
        Payment received = payments.findByPaymentId(paymentId).orElseThrow();
        if (received.getStatus() == PaymentStatus.FAILED || (received.getStatus() == PaymentStatus.PAID && received.getNextFailureLookupAt() == null)) return;
        try {
            List<PgFailedPaymentAttempt> failures = List.copyOf(attemptLookup.findFailed(received.chargeRequest()));
            transaction.executeWithoutResult(tx -> {
                Payment current = payments.lockByPaymentId(paymentId).orElseThrow();
                if (current.getStatus() == PaymentStatus.FAILED) return;
                Instant at = Payment.now();
                for (PgFailedPaymentAttempt fact : failures) {
                    current.requireFailedAttempt(fact, at);
                    FailedPaymentAttempt known = failedAttempts.findBySupplierAttemptId(fact.supplierAttemptId()).orElse(null);
                    if (known == null) failedAttempts.save(FailedPaymentAttempt.record(current, fact, at));
                    else known.requireSame(fact);
                }
                // A response started before approval cannot complete the approval's later history lookup.
                current.recordFailureLookup(current.getStatus() == received.getStatus(), at, at.plus(retryDelay));
                entityManager.flush();
            });
        } catch (RuntimeException incomplete) {
            log.warn("Payment attempt history remains incomplete: paymentId={}, errorType={}", paymentId, incomplete.getClass().getSimpleName());
            transaction.executeWithoutResult(tx -> {
                Payment current = payments.lockByPaymentId(paymentId).orElseThrow();
                if (current.getStatus() != PaymentStatus.FAILED) {
                    Instant at = Payment.now();
                    current.recordFailureLookup(false, at, at.plus(retryDelay));
                }
            });
        }
    }

    private void confirm(Payment current, PgResult result) {
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
    }

    private void defer(String paymentId, String code, RuntimeException failure) {
        log.warn("결제 결과 미확정 paymentId={} code={}", paymentId, code, failure);
        try {
            transaction.executeWithoutResult(tx -> payments.lockByPaymentId(paymentId).ifPresent(payment -> {
                Instant at = Payment.now();
                payment.defer(at.plus(retryDelay), code);
                if ("PORTONE_TEST".equals(provider) && payment.getStatus() != PaymentStatus.FAILED && payment.getNextFailureLookupAt() != null)
                    payment.recordFailureLookup(false, at, at.plus(retryDelay));
            }));
        } catch (RuntimeException unavailable) {
            log.warn("미확정 결제 재시도 예약 실패 paymentId={}", paymentId, unavailable);
        }
    }
}
