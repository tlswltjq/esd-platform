package com.stove.payment.core.service;

import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentTraceContext;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionStatus;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.domain.RefundLookupClaim;
import com.stove.payment.core.domain.RefundLookupInterval;
import com.stove.payment.core.port.PaymentTracing;
import com.stove.payment.core.port.RefundExecutionTraceContexts;
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
    private final RefundExecutionTraceContexts traceContexts;
    private final PaymentTracing tracing;
    private final RefundLookupInterval interval;

    public ResolveRefundLookup(PaymentRepository payments, RefundExecutionRepository executions, ReconcileRefundPayment reconcile,
            PlatformTransactionManager transactions, Clock clock, Executor executor, Duration lease, int batchSize,
            RefundExecutionTraceContexts traceContexts, PaymentTracing tracing) {
        this(payments, executions, reconcile, transactions, clock, executor, lease, batchSize, traceContexts, tracing, RefundLookupInterval.DEFAULT);
    }

    public ResolveRefundLookup(PaymentRepository payments, RefundExecutionRepository executions, ReconcileRefundPayment reconcile,
            PlatformTransactionManager transactions, Clock clock, Executor executor, Duration lease, int batchSize,
            RefundExecutionTraceContexts traceContexts, PaymentTracing tracing, RefundLookupInterval interval) {
        if (lease == null || lease.isNegative() || lease.isZero() || lease.getNano() % 1000 != 0 || batchSize < 1 || batchSize > 100 || interval == null)
            throw new IllegalArgumentException("A bounded lookup batch, positive lease and refund lookup interval are required");
        this.interval = interval;
        this.payments = payments; this.executions = executions; this.reconcile = reconcile; this.clock = clock;
        this.executor = executor; this.lease = lease; this.batchSize = batchSize;
        this.traceContexts = traceContexts; this.tracing = tracing;
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
            // A failed execution keeps a low-frequency lookup for a late full cancellation (decision 128).
            if (!"PORTONE_TEST".equals(payment.getProvider()) || execution.getRefundConfirmedAt() != null
                    || execution.getStatus() == RefundExecutionStatus.SUCCEEDED) return null;
            payment.requireRefundRequest(execution.request());
            return execution.getLookupSchedule().claim(UUID.randomUUID().toString(), clock.instant(), lease);
        });
        if (claim == null) return;
        try {
            // Each lookup is a new child of the parent saved at reception (decision 28, D-050); none means a new root. The
            // post-failure watch (decision 128) runs through the same path, so it keeps the same parent.
            executor.execute(() -> tracing.run(parent(attemptId), received.getPaymentId(), () -> {
                try { reconcile.executeScheduled(attemptId); }
                catch (RuntimeException failure) { logFailure(attemptId, failure); }
                finally {
                    try { update(received, execution -> execution.finishLookup(claim, clock.instant(), interval)); }
                    catch (RuntimeException failure) { logFailure(attemptId, failure); }
                }
            }));
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

    private PaymentTraceContext parent(String attemptId) {
        // Tracing metadata never blocks the lookup or its lease release.
        try { return traceContexts.find(attemptId); }
        catch (RuntimeException failure) { logFailure(attemptId, failure); return PaymentTraceContext.NONE; }
    }

    private static void logFailure(String attemptId, RuntimeException failure) {
        LOG.warn("Refund lookup remains pending: attemptId={}, errorType={}", attemptId, failure.getClass().getSimpleName());
    }
}
