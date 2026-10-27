package com.stove.payment.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PgRefundLookupRequest;
import com.stove.payment.core.domain.PgUnavailable;
import com.stove.payment.core.domain.PreparedRefundCancellation;
import com.stove.payment.core.domain.RefundCancellationDispatch;
import com.stove.payment.core.domain.RefundCancellationPermission;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionStatus;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.port.RefundCancellationGateway;
import java.time.Clock;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public class RequestRefundCancellation {
    private final PaymentRepository payments;
    private final RefundExecutionRepository executions;
    private final RefundCancellationGateway gateway;
    private final ReconcileRefundPayment reconcile;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final RecordRefundCancellationSuccess successes;

    public RequestRefundCancellation(PaymentRepository payments, RefundExecutionRepository executions,
            RefundCancellationGateway gateway, ReconcileRefundPayment reconcile, PlatformTransactionManager transactions, Clock clock,
            RecordRefundCancellationSuccess successes) {
        this.payments = payments; this.executions = executions; this.gateway = gateway; this.reconcile = reconcile; this.clock = clock;
        transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.successes = successes;
    }

    public void execute(String attemptId) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Cancellation preparation must commit before any supplier interaction");
        RefundExecution received = executions.findByAttemptId(attemptId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (received.getRefundConfirmedAt() != null || received.getStatus() == RefundExecutionStatus.FAILED) return;
        Payment paid = payments.findByPaymentId(received.getPaymentId()).orElseThrow();
        paid.requireRefundRequest(received.request());
        if (!"PORTONE_TEST".equals(paid.getProvider())) throw new BusinessException(ErrorCode.CONFLICT);
        PgRefundLookupRequest purchase = new PgRefundLookupRequest(paid.getPaymentId(), paid.getOrderNo(), paid.getAmount(),
                paid.getCurrency(), paid.getProviderTransactionId(), paid.getProviderOccurredAt());
        String candidate = gateway.prepareBody(received.request());
        PreparedRefundCancellation prepared = transaction.execute(tx -> {
            Payment currentPayment = payments.lockByPaymentId(received.getPaymentId()).orElseThrow();
            RefundExecution current = executions.lockByAttemptId(attemptId).orElseThrow();
            current.requireSameRequest(received.request()); currentPayment.requireRefundRequest(current.request());
            if (current.getRefundConfirmedAt() != null) return null;
            RefundCancellationPermission permission = current.prepareCancellation(clock.instant());
            String body = current.freezeCancellationBody(candidate);
            executions.flush();
            return new PreparedRefundCancellation(permission, body);
        });
        if (prepared == null) return;
        if (!prepared.permission().maySendAt(clock.instant())) { reconcile.execute(attemptId); return; }
        RefundCancellationDispatch observation;
        try {
            observation = gateway.send(prepared.permission(), purchase, prepared.body());
        } catch (PgUnavailable unknown) {
            // A lost response may already have completed the customer's refund.
            reconcile.execute(attemptId);
            if (executions.findByAttemptId(attemptId).orElseThrow().getRefundConfirmedAt() != null) return;
            throw unknown;
        }
        if (observation.success() != null) { successes.execute(attemptId, observation.success()); return; }
        if (observation.receipt() != null) {
            transaction.executeWithoutResult(tx -> {
                Payment currentPayment = payments.lockByPaymentId(received.getPaymentId()).orElseThrow();
                executions.lockByAttemptId(attemptId).orElseThrow().recordCancellationReceipt(currentPayment, observation.receipt(), clock.instant());
                executions.flush();
            });
        }
        if (observation.equals(RefundCancellationDispatch.BALANCE_CHANGED)) {
            transaction.executeWithoutResult(tx -> {
                executions.lockByAttemptId(attemptId).orElseThrow().blockAutomaticCancellation(clock.instant());
                executions.flush();
            });
        }
        reconcile.execute(attemptId);
    }
}
