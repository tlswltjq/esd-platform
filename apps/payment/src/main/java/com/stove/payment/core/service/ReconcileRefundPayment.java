package com.stove.payment.core.service;

import com.stove.common.kafka.contract.payload.RefundPaymentConfirmedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PgRefundConfirmation;
import com.stove.payment.core.domain.PgRefundFailureConfirmation;
import com.stove.payment.core.domain.RefundLookupResult;
import com.stove.payment.core.domain.RefundExecutionStatus;
import com.stove.common.kafka.contract.payload.RefundExecutionFailedEvent;
import com.stove.payment.core.domain.PgRefundLookupRequest;
import com.stove.payment.core.domain.RefundCancellation;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.port.RefundPaymentLookup;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies the supplier outside locks, then atomically records money evidence and its notification. */
public class ReconcileRefundPayment {
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

    public void execute(String attemptId) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Refund supplier lookup must run outside a transaction");
        RefundExecution received = executions.findByAttemptId(attemptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (received.getRefundConfirmedAt() != null || received.getStatus() == RefundExecutionStatus.FAILED) return;
        Payment payment = payments.findByPaymentId(received.getPaymentId()).orElseThrow();
        payment.requireRefundRequest(received.request());
        if (!"PORTONE_TEST".equals(payment.getProvider())) throw new BusinessException(ErrorCode.CONFLICT);
        PgRefundLookupRequest purchase = new PgRefundLookupRequest(payment.getPaymentId(), payment.getOrderNo(), payment.getAmount(),
                payment.getCurrency(), payment.getProviderTransactionId(), payment.getProviderOccurredAt());
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
                outbox.record("Payment", current.getOrderNo(), new RefundExecutionFailedEvent(current.getFailureEventId(),
                        current.getFailureConfirmedAt(), current.getRefundId(), current.getAttemptId(), current.getRequestEventId(),
                        current.getOrderNo(), current.getPaymentId(), current.getBuyerSubject(), current.getAmount(), current.getCurrency(),
                        currentPayment.getProviderTransactionId(), current.getReceiptCancellationId(), current.getFailureLookupHash()));
                executions.flush(); return;
            }
            if (!current.confirmRefund(currentPayment, verified.orElseThrow(), UUID.randomUUID().toString(), clock.instant())) return;
            outbox.record("Payment", current.getOrderNo(), new RefundPaymentConfirmedEvent(current.getRefundConfirmationEventId(),
                    current.getRefundConfirmedAt(), current.getRefundId(), current.getAttemptId(), current.getRequestEventId(),
                    current.getOrderNo(), current.getPaymentId(), current.getBuyerSubject(), current.getAmount(), current.getCurrency(),
                    "PG_QUERY_UNATTRIBUTED", currentPayment.getProviderTransactionId(),
                    current.getConfirmedCancellations().stream().map(RefundCancellation::getCancellationId).toList()));
            executions.flush();
        });
    }
}
