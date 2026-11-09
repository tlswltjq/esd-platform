package com.stove.payment.core.service;

import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentApprovalRepository;
import com.stove.payment.core.domain.PaymentApprovalStatus;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private final PaymentApprovalRepository approvals;
    private final Clock approvalClock;

    public ResolvePayment(PaymentRepository payments, PgGateway gateway, OutboxRecorder outbox,
            EntityManager entityManager, PlatformTransactionManager transactions, int batchSize, Duration retryDelay,
            PaymentTraceContexts traceContexts, PaymentTracing tracing, String provider,
            PaymentAttemptLookup attemptLookup, FailedPaymentAttemptRepository failedAttempts,
            PaymentApprovalRepository approvals, Clock approvalClock) {
        if (approvals == null || approvalClock == null) throw new IllegalArgumentException("Approval closing on payment success is required");
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
        this.approvals = approvals;
        this.approvalClock = approvalClock;
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
        if (received.getStatus() == PaymentStatus.FAILED || received.getStatus() == PaymentStatus.EXPIRED || (received.getStatus() == PaymentStatus.PAID && received.getNextFailureLookupAt() == null)) return;
        try {
            List<PgFailedPaymentAttempt> failures = List.copyOf(attemptLookup.findFailed(received.chargeRequest()));
            transaction.executeWithoutResult(tx -> {
                Payment current = payments.lockByPaymentId(paymentId).orElseThrow();
                if (current.getStatus() == PaymentStatus.FAILED || current.getStatus() == PaymentStatus.EXPIRED) return;
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
                if (current.getStatus() != PaymentStatus.FAILED && current.getStatus() != PaymentStatus.EXPIRED) {
                    Instant at = Payment.now();
                    current.recordFailureLookup(false, at, at.plus(retryDelay));
                }
            });
        }
    }

    private void confirm(Payment current, PgResult result) {
        Instant confirmedAt = Payment.now();
        if (!current.confirm(result, UUID.randomUUID().toString(), confirmedAt)) return;
        PaymentResultOutbox.record(outbox, current);
        if (current.getStatus() == PaymentStatus.PAID) succeedAdmittedApproval(current, confirmedAt);
    }

    /**
     * D-051: the admitted approval behind a newly PAID payment closes in the same commit, under the same
     * payment-then-approval lock order the approval worker uses. Without this a held approval stayed REVIEW_REQUIRED until
     * its next held lookup (decision 122) and kept showing its old observation as the customer notice (decision 123), the
     * hold list and the gauge. The rule is the worker's own (an admitted approval of a PAID payment succeeds); only the
     * timing moves. Unadmitted approvals never reach here: the PortOne gateway refuses them and they stay held.
     */
    private void succeedAdmittedApproval(Payment paid, Instant confirmedAt) {
        // The approval timeline uses the approval clock (as the approval worker does); it never closes before the payment
        // confirmation it reflects. Both read the same system clock in production.
        Instant approvalNow = approvalClock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant at = approvalNow.isAfter(confirmedAt) ? approvalNow : confirmedAt;
        approvals.findFirstByPaymentIdAndStatusInOrderByIdDesc(paid.getPaymentId(),
                        List.of(PaymentApprovalStatus.ADMITTED, PaymentApprovalStatus.REVIEW_REQUIRED))
                .flatMap(found -> approvals.lockByApprovalId(found.getApprovalId()))
                .filter(approval -> approval.getAdmittedAt() != null && !approval.terminal())
                .ifPresent(approval -> approval.succeed(paid, at));
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
