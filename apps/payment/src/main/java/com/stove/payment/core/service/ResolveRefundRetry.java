package com.stove.payment.core.service;

import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.domain.RefundCancellationClaim;
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

    public ResolveRefundRetry(RefundExecutionRepository executions, RequestRefundCancellation cancellations,
            Clock clock, Executor executor, int batchSize) {
        if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("A bounded retry scan is required");
        this.executions = executions; this.cancellations = cancellations; this.clock = clock;
        this.executor = executor; this.batchSize = batchSize;
    }
    public void execute() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Retry scans cannot hold a transaction");
        for (String attemptId : executions.findRetryCandidates(clock.instant(), PageRequest.of(0, batchSize))) {
            try {
                RefundCancellationClaim claim = cancellations.claimRetry(attemptId);
                if (claim == null) continue;
                try {
                    executor.execute(() -> {
                        try { cancellations.executeRetry(attemptId, claim); }
                        catch (RuntimeException failure) { logFailure(attemptId, failure); }
                    });
                } catch (RejectedExecutionException full) { cancellations.abandonRetry(attemptId, claim); }
            } catch (RuntimeException failure) { logFailure(attemptId, failure); }
        }
    }

    private static void logFailure(String attemptId, RuntimeException failure) {
        LOG.warn("Refund retry remains unconfirmed: attemptId={}, errorType={}", attemptId, failure.getClass().getSimpleName());
    }
}
