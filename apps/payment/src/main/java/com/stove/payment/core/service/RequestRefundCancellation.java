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
import com.stove.payment.core.domain.RefundCancellationClaim;
import com.stove.payment.core.domain.RefundCancellationHold;
import com.stove.payment.core.domain.RefundCancellationHoldRepository;
import com.stove.payment.core.port.RefundCancellationGateway;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import com.stove.payment.core.domain.PgRefundCancellationReceipt;
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
    private final Duration lease;
    private final RefundCancellationHoldRepository holds;
    private final RecordRefundPreparation preparationEvents;

    public RequestRefundCancellation(PaymentRepository payments, RefundExecutionRepository executions,
            RefundCancellationGateway gateway, ReconcileRefundPayment reconcile, PlatformTransactionManager transactions, Clock clock,
            RecordRefundCancellationSuccess successes, Duration lease, RefundCancellationHoldRepository holds, RecordRefundPreparation preparationEvents) {
        if (lease == null || lease.isNegative() || lease.isZero() || lease.getNano() % 1000 != 0)
            throw new IllegalArgumentException("A bounded dispatch lease is required");
        this.payments = payments; this.executions = executions; this.gateway = gateway; this.reconcile = reconcile; this.clock = clock;
        this.lease = lease;
        this.holds = holds; this.preparationEvents=preparationEvents;
        transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.successes = successes;
    }

    private List<PgRefundCancellationReceipt> previousFailures(Payment payment, RefundExecution current) {
        List<PgRefundCancellationReceipt> failures = new ArrayList<>(); Set<String> visited = new HashSet<>();
        String previousId = current.getPreviousAttemptId();
        while (previousId != null) {
            if (!visited.add(previousId)) throw new BusinessException(ErrorCode.CONFLICT, "이전 실행 연결이 순환합니다.");
            RefundExecution previous = executions.findByAttemptId(previousId).orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT));
            payment.requireRefundRequest(previous.request());
            if (previous.getStatus() != RefundExecutionStatus.FAILED || previous.getFailureEventId() == null
                    || previous.getFailureConfirmedAt() == null || !previous.getRefundId().equals(current.getRefundId())
                    || previous.cancellationReceipt(payment) == null)
                throw new BusinessException(ErrorCode.CONFLICT, "검증된 이전 실패 근거가 필요합니다.");
            failures.add(previous.cancellationReceipt(payment)); previousId = previous.getPreviousAttemptId();
        }
        return List.copyOf(failures);
    }

    /** Kafka redelivery must not implicitly choose a POST retry policy for an ambiguous dispatch. */
    public void executeFirst(String attemptId) { execute(attemptId, true, null); }

    public void execute(String attemptId) { execute(attemptId, false, null); }

    public void executeRetry(String attemptId, RefundCancellationClaim claim) {
        if (claim == null) throw new IllegalArgumentException("A committed retry claim is required");
        try { execute(attemptId, false, claim); }
        finally { finishRetry(attemptId, claim); }
    }

    public RefundCancellationClaim claimRetry(String attemptId) {
        requireNoOuterTransaction();
        RefundExecution received = executions.findByAttemptId(attemptId).orElseThrow();
        return transaction.execute(tx -> {
            Payment payment = payments.lockByPaymentId(received.getPaymentId()).orElseThrow();
            RefundExecution current = executions.lockByAttemptId(attemptId).orElseThrow();
            payment.requireRefundRequest(current.request());
            if (!"PORTONE_TEST".equals(payment.getProvider()) || finished(current)
                    || current.getFirstCancellationPreparedAt() == null || current.getCancellationRequestBody() == null) return null;
            RefundCancellationPermission permission = current.prepareCancellation(clock.instant());
            preparationEvents.execute(current);
            if (permission.automaticCancellationStopped()) { current.getCancellationSchedule().stop(); return null; }
            current.getCancellationSchedule().initializeUnknown(clock.instant());
            return current.getCancellationSchedule().claim(UUID.randomUUID().toString(), clock.instant(), lease, true);
        });
    }

    public void abandonRetry(String attemptId, RefundCancellationClaim claim) {
        updateSchedule(attemptId, current -> current.getCancellationSchedule().abandon(claim));
    }

    public void finishRetry(String attemptId, RefundCancellationClaim claim) {
        updateSchedule(attemptId, current -> {
            if (finished(current)) { current.getCancellationSchedule().stop(); return; }
            RefundCancellationPermission permission = current.prepareCancellation(clock.instant());
            preparationEvents.execute(current);
            if (permission.automaticCancellationStopped()) { current.getCancellationSchedule().stop(); return; }
            current.getCancellationSchedule().finish(claim, clock.instant());
        });
    }

    private void updateSchedule(String attemptId, Consumer<RefundExecution> change) {
        requireNoOuterTransaction();
        RefundExecution received = executions.findByAttemptId(attemptId).orElseThrow();
        transaction.executeWithoutResult(tx -> {
            payments.lockByPaymentId(received.getPaymentId()).orElseThrow();
            change.accept(executions.lockByAttemptId(attemptId).orElseThrow());
        });
    }

    private static boolean finished(RefundExecution execution) {
        return execution.getRefundConfirmedAt() != null || execution.getStatus() == RefundExecutionStatus.FAILED;
    }

    private static void requireNoOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Cancellation preparation must commit before any supplier interaction");
    }

    private void execute(String attemptId, boolean firstOnly, RefundCancellationClaim retryClaim) {
        requireNoOuterTransaction();
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
            if (finished(current)) return null;
            boolean alreadyPrepared = current.getFirstCancellationPreparedAt() != null;
            RefundCancellationPermission base = current.prepareCancellation(clock.instant());
            preparationEvents.execute(current);
            if (firstOnly && alreadyPrepared) { executions.flush(); return null; }
            if (base.automaticCancellationStopped()) { executions.flush(); return null; }
            current.freezeCancellationBody(candidate);
            RefundCancellationClaim owner = retryClaim;
            if (owner == null) owner = current.getCancellationSchedule().claim(UUID.randomUUID().toString(), clock.instant(), lease, false);
            if (owner == null || !current.getCancellationSchedule().ownsAt(owner, clock.instant())) return null;
            RefundCancellationPermission permission = new RefundCancellationPermission(base.request(), base.firstPreparedAt(),
                    base.automaticCancellationStopped(), previousFailures(currentPayment, current), current.getCancellationSchedule().getLeaseUntil());
            String body = current.getCancellationRequestBody();
            executions.flush();
            return new PreparedRefundCancellation(permission, body, owner);
        });
        if (prepared == null) {
            reconcile.execute(attemptId);
            return;
        }
        try { dispatch(attemptId, paid, purchase, prepared); }
        finally { if (retryClaim == null) finishRetry(attemptId, prepared.claim()); }
    }

    private void dispatch(String attemptId, Payment paid, PgRefundLookupRequest purchase, PreparedRefundCancellation prepared) {
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
                Payment currentPayment = payments.lockByPaymentId(paid.getPaymentId()).orElseThrow();
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
        if (observation.equals(RefundCancellationDispatch.FORBIDDEN) || observation.equals(RefundCancellationDispatch.INVALID_REQUEST))
            recordRejection(attemptId, prepared.claim(), observation);
        reconcile.execute(attemptId);
    }

    private void recordRejection(String attemptId, RefundCancellationClaim claim, RefundCancellationDispatch observation) {
        RefundExecution received = executions.findByAttemptId(attemptId).orElseThrow();
        transaction.executeWithoutResult(tx -> {
            Payment payment = payments.lockByPaymentId(received.getPaymentId()).orElseThrow();
            RefundExecution current = executions.lockByAttemptId(attemptId).orElseThrow();
            payment.requireRefundRequest(current.request());
            RefundCancellationHold known = holds.findByAttemptIdAndClaimId(attemptId, claim.token()).orElse(null);
            if (known != null) { known.requireObservation(attemptId, claim); return; }
            boolean activate = !finished(current) && current.getCancellationSchedule().ownsClaim(claim);
            RefundCancellationHold hold = observation.equals(RefundCancellationDispatch.FORBIDDEN)
                    ? RefundCancellationHold.forbidden(attemptId, claim, clock.instant(), activate)
                    : RefundCancellationHold.invalidRequest(attemptId, claim, clock.instant(), activate);
            holds.saveAndFlush(hold);
            if (activate) current.holdCancellation(hold.getHoldId(), claim);
            executions.flush();
        });
    }
}
