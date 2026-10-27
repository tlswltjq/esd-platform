package com.stove.payment.core.service;

import com.stove.common.kafka.contract.payload.RefundExecutionSucceededEvent;
import com.stove.common.kafka.contract.payload.RefundPaymentConfirmedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PgRefundCancellationSuccess;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public class RecordRefundCancellationSuccess {
    private final PaymentRepository payments;
    private final RefundExecutionRepository executions;
    private final OutboxRecorder outbox;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public RecordRefundCancellationSuccess(PaymentRepository payments, RefundExecutionRepository executions, OutboxRecorder outbox,
            PlatformTransactionManager transactions, Clock clock) {
        this.payments = payments; this.executions = executions; this.outbox = outbox; this.clock = clock;
        transaction = new TransactionTemplate(transactions); transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public void execute(String attemptId, PgRefundCancellationSuccess success) {
        if (success == null || !success.attemptId().equals(attemptId)) throw new IllegalArgumentException("Success attempt does not match");
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Success must own its commit");
        transaction.executeWithoutResult(tx -> {
            Payment payment = payments.lockByPaymentId(success.purchase().paymentId()).orElseThrow();
            RefundExecution current = executions.lockByAttemptId(attemptId).orElseThrow();
            if (!current.confirmCancellationSuccess(payment, success, UUID.randomUUID().toString(), clock.instant())) return;
            if (current.getRefundConfirmationEventId().equals(current.getCancellationSuccessEventId())) {
                outbox.record("Payment", current.getOrderNo(), new RefundPaymentConfirmedEvent(current.getRefundConfirmationEventId(),
                        current.getRefundConfirmedAt(), current.getRefundId(), current.getAttemptId(), current.getRequestEventId(),
                        current.getOrderNo(), current.getPaymentId(), current.getBuyerSubject(), current.getAmount(), current.getCurrency(),
                        "PG_RESPONSE", payment.getProviderTransactionId(), List.of(current.getSuccessfulCancellationId())));
            } else {
                outbox.record("Payment", current.getOrderNo(), new RefundExecutionSucceededEvent(current.getCancellationSuccessEventId(),
                        current.getCancellationSucceededAt(), current.getRefundConfirmationEventId(), current.getRefundId(), current.getAttemptId(),
                        current.getRequestEventId(), current.getOrderNo(), current.getPaymentId(), current.getBuyerSubject(), current.getAmount(),
                        current.getCurrency(), payment.getProviderTransactionId(), current.getSuccessfulCancellationId()));
            }
            executions.flush();
        });
    }
}
