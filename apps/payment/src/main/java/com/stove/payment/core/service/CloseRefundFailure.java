package com.stove.payment.core.service;

import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentErrorCode;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PgRefundCancellationReceipt;
import com.stove.payment.core.domain.PgRefundLookupRequest;
import com.stove.payment.core.domain.PgRefundUncancelled;
import com.stove.payment.core.domain.RefundClosureRepository;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.domain.RefundExecutionRequest;
import com.stove.payment.core.domain.RefundFailedCancellation;
import com.stove.payment.core.domain.RefundFailureClosure;
import com.stove.payment.core.domain.RefundFailureClosureReceipt;
import com.stove.payment.core.domain.RefundFailureClosureRepository;
import com.stove.payment.core.domain.RefundFailureClosureRequest;
import com.stove.payment.core.port.RefundPaymentLookup;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Operator failure closure of a refund execution whose automatic POST stopped (decision 128). Same shape as decisions 115
 * and 127: take the lookup worker's lease, re-inspect the PG with no transaction open, then lock payment → execution and
 * close atomically. It closes only when the original payment is PAID with no cancelled amount and every cancellation the
 * PG lists is FAILED. The execution then fails like a PG-confirmed failure and the existing {@code RefundExecutionFailed}
 * leads Order to restore the license and allow the customer's retry. It never cancels at the PG.
 */
@Slf4j
public class CloseRefundFailure {
    private final PaymentRepository payments;
    private final RefundExecutionRepository executions;
    private final RefundFailureClosureRepository closures;
    private final RefundClosureRepository delayClosures;
    private final RefundPaymentLookup lookup;
    private final OutboxRecorder outbox;
    private final Clock clock;
    private final Duration lease;
    private final TransactionTemplate transaction;

    public CloseRefundFailure(PaymentRepository payments, RefundExecutionRepository executions, RefundFailureClosureRepository closures,
            RefundClosureRepository delayClosures, RefundPaymentLookup lookup, OutboxRecorder outbox, Clock clock, Duration lease,
            PlatformTransactionManager transactions) {
        if (lease == null || lease.isNegative() || lease.isZero() || lease.getNano() % 1000 != 0)
            throw new IllegalArgumentException("A bounded database-precision closure lease is required");
        this.payments = payments; this.executions = executions; this.closures = closures; this.delayClosures = delayClosures;
        this.lookup = lookup; this.outbox = outbox; this.clock = clock; this.lease = lease;
        transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public RefundFailureClosureReceipt execute(RefundFailureClosureRequest request) {
        if (request == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Refund failure closure inspects the PG without a transaction");
        RefundFailureClosure known = closures.findByRequestId(request.requestId()).orElse(null);
        if (known != null) return replay(known, request);
        String token = UUID.randomUUID().toString();
        Claim claim = locked(request.attemptId(), (payment, execution) -> {
            RefundFailureClosure prior = closures.findByRequestId(request.requestId()).orElse(null);
            if (prior != null) return Claim.known(prior);
            if (!"PORTONE_TEST".equals(payment.getProvider())) throw new BusinessException(ErrorCode.NOT_FOUND);
            payment.requireRefundRequest(execution.request());
            Instant at = now();
            if (closures.findByAttemptId(execution.getAttemptId()).isPresent() || delayClosures.findByAttemptId(execution.getAttemptId()).isPresent()
                    || !execution.failureClosableAt(at))
                throw new BusinessException(PaymentErrorCode.REFUND_NOT_FAILURE_CLOSABLE);
            boolean leased = execution.getLookupSchedule().getNextAt() != null;
            if (leased && !execution.getLookupSchedule().claimForClosure(token, at, lease)) throw new BusinessException(PaymentErrorCode.REFUND_CLOSURE_BUSY);
            return Claim.owned(PgRefundLookupRequest.from(payment), execution.cancellationReceipt(payment), execution.request(), leased);
        });
        if (claim.prior() != null) return replay(claim.prior(), request);

        PgRefundUncancelled observed;
        try {
            observed = lookup.inspectUncancelled(claim.purchase()).orElse(null);
            // Our own cancellation, if the PG ever returned one, must be listed and FAILED with the same evidence.
            if (observed != null && claim.receipt() != null && !linkedFailed(observed, claim.receipt()))
                throw new IllegalArgumentException("The linked cancellation is missing from the PG history");
        } catch (IllegalArgumentException invalid) {
            throw refuse(request, token, PaymentErrorCode.CLOSURE_PG_INVALID_EVIDENCE, invalid);
        } catch (RuntimeException unavailable) {
            throw refuse(request, token, PaymentErrorCode.CLOSURE_PG_UNAVAILABLE, unavailable);
        }
        Instant observedAt = now();
        // A cancelled amount, a requested or successful cancellation: money may have moved, so the attempt is not failed.
        if (observed == null) throw refuse(request, token, PaymentErrorCode.REFUND_FAILURE_CLOSURE_PG_NOT_UNCANCELLED, null);

        try {
            return locked(request.attemptId(), (payment, execution) -> {
                Instant at = now();
                if (claim.leased() && !execution.getLookupSchedule().ownsClosure(token, at)) throw new BusinessException(PaymentErrorCode.REFUND_CLOSURE_BUSY);
                execution.requireSameRequest(claim.request());
                RefundFailureClosure prior = closures.findByRequestId(request.requestId()).orElse(null);
                if (prior != null) { execution.getLookupSchedule().releaseClosure(token); prior.requireSame(request); return receipt(prior, execution); }
                // A resumed hold, a new PG receipt or a POST started since the claim makes the observation stale.
                if (!execution.failureClosableAt(at) || closures.findByAttemptId(execution.getAttemptId()).isPresent()
                        || delayClosures.findByAttemptId(execution.getAttemptId()).isPresent()
                        || !Objects.equals(execution.cancellationReceipt(payment), claim.receipt()))
                    throw new BusinessException(PaymentErrorCode.REFUND_NOT_FAILURE_CLOSABLE);
                RefundFailureClosure.StopReason reason = execution.getActiveCancellationHoldId() != null ? RefundFailureClosure.StopReason.CANCELLATION_HOLD
                        : execution.getCancellationBalanceChangedAt() != null ? RefundFailureClosure.StopReason.BALANCE_CHANGED
                        : RefundFailureClosure.StopReason.WINDOW_ENDED;
                execution.closeAsFailed(payment, UUID.randomUUID().toString(), observed.snapshotHash(), at);
                // The lookup lease returns to the worker; its next lookup uses the post-failure delay (decision 128).
                execution.getLookupSchedule().releaseClosure(token);
                RefundFailureClosure closure = RefundFailureClosure.record(execution, request, reason, observed, observedAt);
                closures.saveAndFlush(closure);
                RefundConfirmationOutbox.recordFailure(outbox, payment, execution, closure.getFailedCancellationId());
                executions.flush();
                return receipt(closure, execution);
            });
        } catch (DataIntegrityViolationException collision) {
            release(request, token);
            RefundFailureClosure prior = closures.findByRequestId(request.requestId()).orElse(null);
            if (prior != null) return replay(prior, request);
            if (closures.findByAttemptId(request.attemptId()).isPresent()) throw new BusinessException(PaymentErrorCode.REFUND_NOT_FAILURE_CLOSABLE);
            throw collision;
        } catch (BusinessException rejected) {
            if (rejected.errorCode() != PaymentErrorCode.REFUND_CLOSURE_BUSY) release(request, token);
            throw rejected;
        } catch (RuntimeException failure) {
            release(request, token);
            throw failure;
        }
    }

    private static boolean linkedFailed(PgRefundUncancelled observed, PgRefundCancellationReceipt linked) {
        for (RefundFailedCancellation row : observed.failedCancellations())
            if (row.getCancellationId().equals(linked.cancellationId()))
                return row.receipt(linked.purchase(), linked.attemptId()).equals(linked.failed());
        return false;
    }

    private BusinessException refuse(RefundFailureClosureRequest request, String token, PaymentErrorCode code, RuntimeException cause) {
        release(request, token);
        log.warn("Refund failure closure refused: attemptId={}, code={}, errorType={}", request.attemptId(), code.code(),
                cause == null ? "-" : cause.getClass().getSimpleName());
        return new BusinessException(code);
    }

    private void release(RefundFailureClosureRequest request, String token) {
        try {
            locked(request.attemptId(), (payment, execution) -> { execution.getLookupSchedule().releaseClosure(token); return null; });
        } catch (RuntimeException unavailable) {
            log.warn("Refund failure closure lease remains until expiry: attemptId={}, errorType={}", request.attemptId(), unavailable.getClass().getSimpleName());
        }
    }

    private RefundFailureClosureReceipt replay(RefundFailureClosure known, RefundFailureClosureRequest request) {
        known.requireSame(request);
        return receipt(known, executions.findByAttemptId(known.getAttemptId()).orElseThrow());
    }

    private static RefundFailureClosureReceipt receipt(RefundFailureClosure closure, RefundExecution execution) {
        // Status and time come from the closure record, so a replay after a late observation returns the first response.
        return new RefundFailureClosureReceipt(closure.view(), execution.getRefundId(), execution.getOrderNo(), execution.getPaymentId(),
                closure.getExecutionStatus(), closure.getFailureConfirmedAt());
    }

    private <T> T locked(String attemptId, BiFunction<Payment, RefundExecution, T> action) {
        String paymentId = executions.findPaymentIdByAttemptId(attemptId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return transaction.execute(tx -> {
            Payment payment = payments.lockByPaymentId(paymentId).orElseThrow();
            RefundExecution execution = executions.lockByAttemptId(attemptId).orElseThrow();
            return action.apply(payment, execution);
        });
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }

    private record Claim(PgRefundLookupRequest purchase, PgRefundCancellationReceipt receipt, RefundExecutionRequest request,
            boolean leased, RefundFailureClosure prior) {
        static Claim owned(PgRefundLookupRequest purchase, PgRefundCancellationReceipt receipt, RefundExecutionRequest request, boolean leased) {
            return new Claim(purchase, receipt, request, leased, null);
        }
        static Claim known(RefundFailureClosure prior) { return new Claim(null, null, null, false, prior); }
    }
}
