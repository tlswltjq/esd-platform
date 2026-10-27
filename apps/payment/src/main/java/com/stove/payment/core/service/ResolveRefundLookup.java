package com.stove.payment.core.service;

import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionStatus;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.domain.RefundLookupClaim;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Claims before submission; HTTP runs outside transactions and cannot send a cancellation. */
public class ResolveRefundLookup {
    private static final Logger LOG = LoggerFactory.getLogger(ResolveRefundLookup.class);
    private final PaymentRepository payments;
    private final RefundExecutionRepository executions;
    private final ReconcileRefundPayment reconcile;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Executor executor;
    private final Duration lease;
    private final int batchSize;

    public ResolveRefundLookup(PaymentRepository payments, RefundExecutionRepository executions, ReconcileRefundPayment reconcile,
            PlatformTransactionManager transactions, Clock clock, Executor executor, Duration lease, int batchSize) {
        if (lease == null || lease.isNegative() || lease.isZero() || lease.getNano() % 1000 != 0 || batchSize < 1 || batchSize > 100)
            throw new IllegalArgumentException("A bounded lookup batch and positive lease are required");
        this.payments = payments; this.executions = executions; this.reconcile = reconcile; this.clock = clock;
        this.executor = executor; this.lease = lease; this.batchSize = batchSize;
        transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public void execute() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Refund lookup scanning must run outside a transaction");
        for (String attemptId : executions.findDueLookups(clock.instant(), PageRequest.of(0, batchSize))) {
            try { submit(attemptId); }
            catch (RuntimeException failure) { logFailure(attemptId, failure); }
        }
    }

    private void submit(String attemptId) {
        RefundExecution received = executions.findByAttemptId(attemptId).orElseThrow();
        RefundLookupClaim claim = transaction.execute(tx -> {
            Payment payment = payments.lockByPaymentId(received.getPaymentId()).orElseThrow();
            RefundExecution execution = executions.lockByAttemptId(attemptId).orElseThrow();
            if (!"PORTONE_TEST".equals(payment.getProvider()) || execution.getRefundConfirmedAt() != null || execution.getStatus() != RefundExecutionStatus.PENDING) return null;
            payment.requireRefundRequest(execution.request());
            return execution.getLookupSchedule().claim(UUID.randomUUID().toString(), clock.instant(), lease);
        });
        if (claim == null) return;
        try {
            executor.execute(() -> {
                try { reconcile.execute(attemptId); }
                catch (RuntimeException failure) { logFailure(attemptId, failure); }
                finally {
                    try { update(received, execution -> execution.getLookupSchedule()
                            .finish(claim, execution.getFirstCancellationPreparedAt(), clock.instant())); }
                    catch (RuntimeException failure) { logFailure(attemptId, failure); }
                }
            });
        } catch (RejectedExecutionException full) {
            update(received, execution -> execution.getLookupSchedule().abandon(claim));
        }
    }

    private void update(RefundExecution received, Consumer<RefundExecution> change) {
        transaction.executeWithoutResult(tx -> {
            payments.lockByPaymentId(received.getPaymentId()).orElseThrow();
            RefundExecution current = executions.lockByAttemptId(received.getAttemptId()).orElseThrow();
            change.accept(current);
        });
    }

    private static void logFailure(String attemptId, RuntimeException failure) {
        LOG.warn("Refund lookup remains pending: attemptId={}, errorType={}", attemptId, failure.getClass().getSimpleName());
    }
}
