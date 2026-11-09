package com.stove.payment.core.service;

import com.stove.payment.core.domain.PaymentTraceContext;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.domain.RefundCancellationClaim;
import com.stove.payment.core.port.PaymentTracing;
import com.stove.payment.core.port.RefundExecutionTraceContexts;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class ResolveRefundRetry {
    private static final Logger LOG = LoggerFactory.getLogger(ResolveRefundRetry.class);
    private final RefundExecutionRepository executions;
    private final RequestRefundCancellation cancellations;
    private final Clock clock;
    private final Executor executor;
    private final int batchSize;
    private final RefundExecutionTraceContexts traceContexts;
    private final PaymentTracing tracing;

    public ResolveRefundRetry(RefundExecutionRepository executions, RequestRefundCancellation cancellations,
            Clock clock, Executor executor, int batchSize, RefundExecutionTraceContexts traceContexts, PaymentTracing tracing) {
        if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("A bounded retry scan is required");
        this.executions = executions; this.cancellations = cancellations; this.clock = clock;
        this.executor = executor; this.batchSize = batchSize;
        this.traceContexts = traceContexts; this.tracing = tracing;
    }
    public void execute() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Retry scans cannot hold a transaction");
        for (String attemptId : executions.findRetryCandidates(clock.instant(), PageRequest.of(0, batchSize))) {
            try {
                RefundCancellationClaim claim = cancellations.claimRetry(attemptId);
                if (claim == null) continue;
                try {
                    // Each resend is a new child of the parent saved at reception (decision 28, D-050); none means a new root.
                    executor.execute(() -> traced(attemptId, () -> {
                        try { cancellations.executeRetry(attemptId, claim); }
                        catch (RuntimeException failure) { logFailure(attemptId, failure); }
                    }));
                } catch (RejectedExecutionException full) { cancellations.abandonRetry(attemptId, claim); }
            } catch (RuntimeException failure) { logFailure(attemptId, failure); }
        }
    }

    private void traced(String attemptId, Runnable work) {
        PaymentTraceContext parent = PaymentTraceContext.NONE;
        String paymentId = null;
        // Tracing metadata never blocks the resend; executeRetry still owns the claim.
        try {
            paymentId = executions.findByAttemptId(attemptId).map(RefundExecution::getPaymentId).orElse(null);
            parent = traceContexts.find(attemptId);
        } catch (RuntimeException failure) { logFailure(attemptId, failure); }
        tracing.run(parent, paymentId, work);
    }

    private static void logFailure(String attemptId, RuntimeException failure) {
        LOG.warn("Refund retry remains unconfirmed: attemptId={}, errorType={}", attemptId, failure.getClass().getSimpleName());
    }
}
