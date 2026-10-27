package com.stove.payment.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.RefundCancellationPermission;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import java.time.Clock;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists the first dispatch preparation before returning any permission to call PG. */
public class RecordRefundCancellationPreparation {
    private final RefundExecutionRepository executions;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public RecordRefundCancellationPreparation(RefundExecutionRepository executions, PlatformTransactionManager transactions, Clock clock) {
        this.executions = executions;
        this.clock = clock;
        transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public RefundCancellationPermission execute(String attemptId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Refund dispatch preparation must commit before any external call");
        }
        return transaction.execute(tx -> {
            RefundExecution execution = executions.lockByAttemptId(attemptId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "환불 실행 접수가 없습니다."));
            RefundCancellationPermission permission = execution.prepareCancellation(clock.instant());
            executions.flush();
            return permission;
        });
    }
}
