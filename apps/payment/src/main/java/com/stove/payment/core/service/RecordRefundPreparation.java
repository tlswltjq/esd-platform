package com.stove.payment.core.service;

import com.stove.common.kafka.contract.payload.RefundExecutionPreparedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.payment.core.domain.RefundExecution;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor @Transactional(propagation=Propagation.MANDATORY)
public class RecordRefundPreparation {
    private final OutboxRecorder outbox;
    public void execute(RefundExecution execution) {
        if (execution==null) throw new IllegalArgumentException("A prepared execution is required");
        if (execution.recordPreparationPublication())
            outbox.record("Payment",execution.getOrderNo(),new RefundExecutionPreparedEvent(execution.getPreparationEventId(),execution.getFirstCancellationPreparedAt(),
                    execution.getRefundId(),execution.getAttemptId(),execution.getRequestEventId(),execution.getOrderNo(),execution.getPaymentId(),execution.getBuyerSubject(),
                    execution.getAmount(),execution.getCurrency(),execution.getLicenseSuspensionEventId(),execution.getPreviousAttemptId(),
                    execution.getFirstCancellationPreparedAt(),execution.cancellationResumeDeadline()));
    }
}
