package com.stove.payment.core.service;

import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PgRefundConfirmation;
import com.stove.payment.core.domain.PgRefundFailureConfirmation;
import com.stove.payment.core.domain.RefundLookupResult;
import com.stove.payment.core.domain.RefundExecutionStatus;
import com.stove.payment.core.domain.PgRefundLookupRequest;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.port.RefundPaymentLookup;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies the supplier outside locks, then atomically records money evidence and its notification. */
public class ReconcileRefundPayment {
    private static final Logger LOG = LoggerFactory.getLogger(ReconcileRefundPayment.class);
    private final PaymentRepository payments;
    private final RefundExecutionRepository executions;
    private final RefundPaymentLookup lookup;
    private final OutboxRecorder outbox;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ReconcileRefundPayment(PaymentRepository payments, RefundExecutionRepository executions, RefundPaymentLookup lookup,
            OutboxRecorder outbox, PlatformTransactionManager transactions, Clock clock) {
        this.payments = payments; this.executions = executions; this.lookup = lookup; this.outbox = outbox; this.clock = clock;
        transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** The periodic lookup worker's entry: a failed execution is only watched for a late full cancellation (decision 128). */
    public void executeScheduled(String attemptId) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Refund supplier lookup must run outside a transaction");
        RefundExecution received = executions.findByAttemptId(attemptId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (received.getStatus() != RefundExecutionStatus.FAILED) { execute(attemptId); return; }
        Payment payment = payments.findByPaymentId(received.getPaymentId()).orElseThrow();
        payment.requireRefundRequest(received.request());
        if (!"PORTONE_TEST".equals(payment.getProvider())) throw new BusinessException(ErrorCode.CONFLICT);
        watchAfterFailure(attemptId, received, payment, PgRefundLookupRequest.from(payment));
    }

    public void execute(String attemptId) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Refund supplier lookup must run outside a transaction");
        RefundExecution received = executions.findByAttemptId(attemptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (received.getRefundConfirmedAt() != null || received.getStatus() == RefundExecutionStatus.FAILED) return;
        Payment payment = payments.findByPaymentId(received.getPaymentId()).orElseThrow();
        payment.requireRefundRequest(received.request());
        if (!"PORTONE_TEST".equals(payment.getProvider())) throw new BusinessException(ErrorCode.CONFLICT);
        PgRefundLookupRequest purchase = PgRefundLookupRequest.from(payment);
        RefundLookupResult observed = received.cancellationReceipt(payment) == null
                ? new RefundLookupResult(lookup.find(purchase).orElse(null), null)
                : lookup.inspect(purchase, received.cancellationReceipt(payment));
        Optional<PgRefundConfirmation> verified = Optional.ofNullable(observed.confirmation());
        PgRefundFailureConfirmation failed = observed.failure();
        if (verified.isEmpty() && failed == null) return;
        transaction.executeWithoutResult(tx -> {
            Payment currentPayment = payments.lockByPaymentId(payment.getPaymentId()).orElseThrow();
            RefundExecution current = executions.lockByAttemptId(attemptId).orElseThrow();
            current.requireSameRequest(received.request());
            if (failed != null) {
                if (current.getRefundConfirmedAt() != null) return;
                if (!current.confirmFailure(currentPayment, failed, UUID.randomUUID().toString(), clock.instant())) return;
                RefundConfirmationOutbox.recordFailure(outbox, currentPayment, current, current.getReceiptCancellationId());
                executions.flush(); return;
            }
            if (!current.confirmRefund(currentPayment, verified.orElseThrow(), UUID.randomUUID().toString(), clock.instant())) return;
            RefundConfirmationOutbox.recordUnattributed(outbox, currentPayment, current);
            executions.flush();
        });
    }

    /**
     * Decision 128: after a failure (PG-confirmed or closed by an operator) the read-only lookup stays at a low frequency. A
     * late full cancellation is recorded for investigation and the alert only; the status, the restored license and the
     * customer's refund are not changed and no event is published.
     */
    private void watchAfterFailure(String attemptId, RefundExecution received, Payment payment, PgRefundLookupRequest purchase) {
        if (received.getLateCancellationObservedAt() != null || received.getLookupSchedule().getNextAt() == null) return;
        PgRefundConfirmation late = lookup.find(purchase).orElse(null);
        if (late == null) return;
        transaction.executeWithoutResult(tx -> {
            Payment currentPayment = payments.lockByPaymentId(payment.getPaymentId()).orElseThrow();
            RefundExecution current = executions.lockByAttemptId(attemptId).orElseThrow();
            current.requireSameRequest(received.request());
            if (current.getStatus() != RefundExecutionStatus.FAILED || current.getLookupSchedule().getNextAt() == null) return;
            if (current.recordLateCancellation(currentPayment, late, clock.instant().truncatedTo(ChronoUnit.MICROS)))
                LOG.warn("Full cancellation found after the refund failed: attemptId={}, refundId={}", current.getAttemptId(), current.getRefundId());
            executions.flush();
        });
    }
}
