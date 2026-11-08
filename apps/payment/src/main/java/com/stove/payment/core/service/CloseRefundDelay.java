package com.stove.payment.core.service;

import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentErrorCode;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PgRefundCancellationReceipt;
import com.stove.payment.core.domain.PgRefundConfirmation;
import com.stove.payment.core.domain.PgRefundLookupRequest;
import com.stove.payment.core.domain.RefundClosure;
import com.stove.payment.core.domain.RefundClosureReceipt;
import com.stove.payment.core.domain.RefundClosureRepository;
import com.stove.payment.core.domain.RefundClosureRequest;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.domain.RefundExecutionRequest;
import com.stove.payment.core.domain.RefundLookupResult;
import com.stove.payment.core.port.RefundPaymentLookup;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.BiFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Operator closure of a delayed or unattributed refund execution (decision 127). Same shape as the approval closure
 * (decision 115): take the lookup worker's lease (when a worker is scheduled), re-inspect the PG with no transaction open,
 * then lock payment → execution and close atomically. It closes only on a full cancellation of the same original payment. An unconfirmed execution is
 * completed through the existing lookup confirmation; an already unattributed one only records the judgment. It never
 * cancels at the PG.
 */
@Slf4j
public class CloseRefundDelay {
    private final PaymentRepository payments;
    private final RefundExecutionRepository executions;
    private final RefundClosureRepository closures;
    private final RefundPaymentLookup lookup;
    private final OutboxRecorder outbox;
    private final Clock clock;
    private final Duration lease;
    private final TransactionTemplate transaction;

    public CloseRefundDelay(PaymentRepository payments, RefundExecutionRepository executions, RefundClosureRepository closures,
            RefundPaymentLookup lookup, OutboxRecorder outbox, Clock clock, Duration lease, PlatformTransactionManager transactions) {
        if (lease == null || lease.isNegative() || lease.isZero() || lease.getNano() % 1000 != 0)
            throw new IllegalArgumentException("A bounded database-precision closure lease is required");
        this.payments = payments; this.executions = executions; this.closures = closures; this.lookup = lookup;
        this.outbox = outbox; this.clock = clock; this.lease = lease;
        transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public RefundClosureReceipt execute(RefundClosureRequest request) {
        if (request == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Refund closure inspects the PG without a transaction");
        RefundClosure known = closures.findByRequestId(request.requestId()).orElse(null);
        if (known != null) return replay(known, request);
        String token = UUID.randomUUID().toString();
        Claim claim = locked(request.attemptId(), (payment, execution) -> {
            RefundClosure prior = closures.findByRequestId(request.requestId()).orElse(null);
            if (prior != null) return Claim.known(prior);
            if (!"PORTONE_TEST".equals(payment.getProvider())) throw new BusinessException(ErrorCode.NOT_FOUND);
            payment.requireRefundRequest(execution.request());
            Instant at = now();
            if (closures.findByAttemptId(execution.getAttemptId()).isPresent() || !execution.closableAt(at))
                throw new BusinessException(PaymentErrorCode.REFUND_NOT_CLOSABLE);
            // An unconfirmed execution has a running lookup worker; the closure takes its lease. An unattributed one has a
            // stopped schedule and no worker, so the row lock, the attempt UNIQUE and the requestId guard it instead.
            boolean leased = execution.getLookupSchedule().getNextAt() != null;
            if (leased && !execution.getLookupSchedule().claimForClosure(token, at, lease)) throw new BusinessException(PaymentErrorCode.REFUND_CLOSURE_BUSY);
            return Claim.owned(PgRefundLookupRequest.from(payment), execution.cancellationReceipt(payment), execution.request(), leased);
        });
        if (claim.prior() != null) return replay(claim.prior(), request);

        RefundLookupResult observed;
        try {
            // The same read the lookup worker makes: with our cancellation receipt, the linked inspection; otherwise a full-cancellation find.
            observed = claim.receipt() == null ? new RefundLookupResult(lookup.find(claim.purchase()).orElse(null), null)
                    : lookup.inspect(claim.purchase(), claim.receipt());
        } catch (IllegalArgumentException invalid) {
            throw refuse(request, token, PaymentErrorCode.CLOSURE_PG_INVALID_EVIDENCE, invalid);
        } catch (RuntimeException unavailable) {
            throw refuse(request, token, PaymentErrorCode.CLOSURE_PG_UNAVAILABLE, unavailable);
        }
        Instant observedAt = now();
        PgRefundConfirmation confirmation = observed.confirmation();
        // PAID with a balance, a partial cancellation, a pending or failed cancellation: the operator cancels in the console first.
        if (confirmation == null) throw refuse(request, token, PaymentErrorCode.REFUND_CLOSURE_PG_NOT_CANCELLED, null);

        try {
            return locked(request.attemptId(), (payment, execution) -> {
                Instant at = now();
                // A lost lease means another cycle may have acted; nothing from this closure is saved.
                if (claim.leased() && !execution.getLookupSchedule().ownsClosure(token, at)) throw new BusinessException(PaymentErrorCode.REFUND_CLOSURE_BUSY);
                execution.requireSameRequest(claim.request());
                // The same command may have committed while this one inspected the PG (an unattributed closure takes no lease).
                RefundClosure prior = closures.findByRequestId(request.requestId()).orElse(null);
                if (prior != null) { execution.getLookupSchedule().releaseClosure(token); prior.requireSame(request); return receipt(prior, execution); }
                if (!execution.closableAt(at) || closures.findByAttemptId(execution.getAttemptId()).isPresent())
                    throw new BusinessException(PaymentErrorCode.REFUND_NOT_CLOSABLE);
                // The existing completion path: the first lookup confirmation, or a no-op when the same evidence is already stored.
                boolean completed = execution.confirmRefund(payment, confirmation, UUID.randomUUID().toString(), at);
                if (completed) RefundConfirmationOutbox.recordUnattributed(outbox, payment, execution);
                else execution.getLookupSchedule().releaseClosure(token);  // no-op when nothing was leased
                RefundClosure closure = RefundClosure.record(execution, request,
                        completed ? RefundClosure.Outcome.COMPLETED : RefundClosure.Outcome.RECORDED, observedAt, at);
                closures.saveAndFlush(closure);
                executions.flush();
                return receipt(closure, execution);
            });
        } catch (DataIntegrityViolationException collision) {
            release(request, token);
            RefundClosure prior = closures.findByRequestId(request.requestId()).orElse(null);
            if (prior != null) return replay(prior, request);
            if (closures.findByAttemptId(request.attemptId()).isPresent()) throw new BusinessException(PaymentErrorCode.REFUND_NOT_CLOSABLE);
            throw collision;
        } catch (BusinessException rejected) {
            if (rejected.errorCode() != PaymentErrorCode.REFUND_CLOSURE_BUSY) release(request, token);
            throw rejected;
        } catch (RuntimeException failure) {
            release(request, token);
            throw failure;
        }
    }

    private BusinessException refuse(RefundClosureRequest request, String token, PaymentErrorCode code, RuntimeException cause) {
        release(request, token);
        log.warn("Refund closure refused: attemptId={}, code={}, errorType={}", request.attemptId(), code.code(),
                cause == null ? "-" : cause.getClass().getSimpleName());
        return new BusinessException(code);
    }

    /** Returns the lease to the lookup worker; its own next lookup time is untouched. A lease that is no longer ours is left alone. */
    private void release(RefundClosureRequest request, String token) {
        try {
            locked(request.attemptId(), (payment, execution) -> { execution.getLookupSchedule().releaseClosure(token); return null; });
        } catch (RuntimeException unavailable) {
            log.warn("Refund closure lease remains until expiry: attemptId={}, errorType={}", request.attemptId(), unavailable.getClass().getSimpleName());
        }
    }

    private RefundClosureReceipt replay(RefundClosure known, RefundClosureRequest request) {
        known.requireSame(request);
        return receipt(known, executions.findByAttemptId(known.getAttemptId()).orElseThrow());
    }

    private static RefundClosureReceipt receipt(RefundClosure closure, RefundExecution execution) {
        return new RefundClosureReceipt(closure.view(), execution.getRefundId(), execution.getOrderNo(), execution.getPaymentId(),
                execution.getStatus(), execution.getRefundConfirmedAt(), execution.getRefundConfirmationSource());
    }

    /** Locks in the existing order — payment, then execution — like the lookup worker and the investigation record. */
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
            boolean leased, RefundClosure prior) {
        static Claim owned(PgRefundLookupRequest purchase, PgRefundCancellationReceipt receipt, RefundExecutionRequest request, boolean leased) {
            return new Claim(purchase, receipt, request, leased, null);
        }
        static Claim known(RefundClosure prior) { return new Claim(null, null, null, false, prior); }
    }
}
