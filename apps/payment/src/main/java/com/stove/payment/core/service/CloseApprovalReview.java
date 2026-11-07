package com.stove.payment.core.service;

import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.ApprovalClosure;
import com.stove.payment.core.domain.ApprovalClosureReceipt;
import com.stove.payment.core.domain.ApprovalClosureRepository;
import com.stove.payment.core.domain.ApprovalClosureRequest;
import com.stove.payment.core.domain.ApprovalObservation;
import com.stove.payment.core.domain.ApprovalPurchase;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentApproval;
import com.stove.payment.core.domain.PaymentApprovalRepository;
import com.stove.payment.core.domain.PaymentErrorCode;
import com.stove.payment.core.domain.PaymentOrderLifecycle;
import com.stove.payment.core.domain.PaymentOrderLifecycleRepository;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.port.PgPaymentApprovalGateway;
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
 * Operator closure of a held approval (decision 115). The closure takes the worker's lease, re-inspects the PG with no
 * transaction open, then locks payment → approval and ends the purchase atomically. It never cancels or confirms at the PG.
 */
@Slf4j
public class CloseApprovalReview {
    private final PaymentRepository payments;
    private final PaymentApprovalRepository approvals;
    private final ApprovalClosureRepository closures;
    private final PaymentOrderLifecycleRepository lifecycle;
    private final PgPaymentApprovalGateway gateway;
    private final OutboxRecorder outbox;
    private final Clock clock;
    private final Duration lease;
    private final TransactionTemplate transaction;

    public CloseApprovalReview(PaymentRepository payments, PaymentApprovalRepository approvals, ApprovalClosureRepository closures,
            PaymentOrderLifecycleRepository lifecycle, PgPaymentApprovalGateway gateway, OutboxRecorder outbox, Clock clock,
            Duration lease, PlatformTransactionManager transactions) {
        if (lease == null || lease.isNegative() || lease.isZero()) throw new IllegalArgumentException("A bounded closure lease is required");
        this.payments = payments; this.approvals = approvals; this.closures = closures; this.lifecycle = lifecycle;
        this.gateway = gateway; this.outbox = outbox; this.clock = clock; this.lease = lease;
        transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public ApprovalClosureReceipt execute(ApprovalClosureRequest request) {
        if (request == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Approval closure inspects the PG without a transaction");
        ApprovalClosure known = closures.findByRequestId(request.requestId()).orElse(null);
        if (known != null) return replay(known, request);
        String token = UUID.randomUUID().toString();
        Claim claim = locked(request.approvalId(), (payment, approval) -> {
            ApprovalClosure prior = closures.findByRequestId(request.requestId()).orElse(null);
            if (prior != null) return Claim.known(prior);
            Instant scheduled = approval.getNextAt();
            if (!approval.claimForClosure(token, now(), lease)) throw new BusinessException(PaymentErrorCode.APPROVAL_CLOSURE_BUSY);
            return Claim.owned(approval.purchase(), scheduled);
        });
        if (claim.prior() != null) return replay(claim.prior(), request);

        ApprovalObservation observed;
        try { observed = gateway.inspect(claim.purchase()); }
        catch (IllegalArgumentException invalid) { throw refuse(request, token, claim, "INVALID_EVIDENCE", PaymentErrorCode.CLOSURE_PG_INVALID_EVIDENCE, invalid); }
        catch (RuntimeException unavailable) { throw refuse(request, token, claim, "UNAVAILABLE", PaymentErrorCode.CLOSURE_PG_UNAVAILABLE, unavailable); }
        Instant observedAt = now();

        Outcome outcome;
        try {
            outcome = locked(request.approvalId(), (payment, approval) -> {
                Instant at = now();
                // A lost lease means another cycle may have acted; nothing from this closure is saved.
                if (!approval.owns(token, at)) throw new BusinessException(PaymentErrorCode.APPROVAL_CLOSURE_BUSY);
                approval.recordObservation(observed, observedAt);
                PaymentErrorCode refusal = refusal(observed, payment);
                if (refusal != null) {
                    approval.finish(token, at, claim.resumeAt(at));
                    return Outcome.refused(refusal);
                }
                ApprovalClosure closure = ApprovalClosure.record(approval, request, observed, observedAt, at);
                approval.closeAfterReview(observed, token, at);
                if (approval.getAdmittedAt() == null && orderExpired(approval.getOrderNo())) {
                    payment.expireUnpaid();
                } else {
                    payment.failAfterReview(approval.getSupplierAttemptId(), observedAt, closure.reason(), UUID.randomUUID().toString(), at);
                    PaymentResultOutbox.record(outbox, payment);
                }
                closures.saveAndFlush(closure);
                return Outcome.closed(receipt(closure, approval, payment));
            });
        } catch (DataIntegrityViolationException collision) {
            release(request, token, claim);
            ApprovalClosure prior = closures.findByRequestId(request.requestId()).orElseThrow(() -> collision);
            return replay(prior, request);
        } catch (BusinessException rejected) {
            if (rejected.errorCode() != PaymentErrorCode.APPROVAL_CLOSURE_BUSY) release(request, token, claim);
            throw rejected;
        } catch (RuntimeException failure) {
            release(request, token, claim);
            throw failure;
        }
        if (outcome.refusal() != null) throw new BusinessException(outcome.refusal());
        return outcome.receipt();
    }

    /** PAID and PENDING never close a hold; neither does a purchase that already has a final result. */
    private static PaymentErrorCode refusal(ApprovalObservation observed, Payment payment) {
        if (observed.state() == ApprovalObservation.State.PAID) return PaymentErrorCode.CLOSURE_PG_PAID;
        if (!observed.closesReview()) return PaymentErrorCode.CLOSURE_PG_PENDING;
        if (payment.getStatus() != PaymentStatus.PENDING || payment.getResultEventId() != null) return PaymentErrorCode.CLOSURE_PAYMENT_RESOLVED;
        return null;
    }

    private BusinessException refuse(ApprovalClosureRequest request, String token, Claim claim, String error, PaymentErrorCode code, RuntimeException cause) {
        try {
            locked(request.approvalId(), (payment, approval) -> {
                Instant at = now();
                if (approval.owns(token, at)) {
                    approval.recordInspectionFailure(error, at);
                    approval.finish(token, at, claim.resumeAt(at));
                }
                return null;
            });
        } catch (RuntimeException recording) {
            cause.addSuppressed(recording);
        }
        log.warn("Approval closure refused: approvalId={}, code={}, errorType={}", request.approvalId(), code.code(), cause.getClass().getSimpleName());
        return new BusinessException(code);
    }

    /** Returns the lease to the worker schedule when the closure could not finish; a lease that is no longer ours is left alone. */
    private void release(ApprovalClosureRequest request, String token, Claim claim) {
        try {
            locked(request.approvalId(), (payment, approval) -> {
                Instant at = now();
                if (approval.owns(token, at)) approval.finish(token, at, claim.resumeAt(at));
                return null;
            });
        } catch (RuntimeException unavailable) {
            log.warn("Approval closure lease remains until expiry: approvalId={}, errorType={}", request.approvalId(), unavailable.getClass().getSimpleName());
        }
    }

    private ApprovalClosureReceipt replay(ApprovalClosure known, ApprovalClosureRequest request) {
        known.requireSame(request);
        PaymentApproval approval = approvals.findByApprovalId(known.getApprovalId()).orElseThrow();
        Payment payment = payments.findByPaymentId(approval.getPaymentId()).orElseThrow();
        return receipt(known, approval, payment);
    }

    private static ApprovalClosureReceipt receipt(ApprovalClosure closure, PaymentApproval approval, Payment payment) {
        return new ApprovalClosureReceipt(closure.view(), approval.getOrderNo(), approval.getPaymentId(), approval.getStatus(), payment.getStatus());
    }

    private boolean orderExpired(String orderNo) {
        return lifecycle.findById(orderNo).map(PaymentOrderLifecycle::expired).orElse(false);
    }

    /** Locks in the existing order — payment, then approval — like the worker and the investigation record. */
    private <T> T locked(String approvalId, BiFunction<Payment, PaymentApproval, T> action) {
        String paymentId = approvals.findPaymentIdByApprovalId(approvalId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return transaction.execute(tx -> {
            Payment payment = payments.lockByPaymentId(paymentId).orElseThrow();
            PaymentApproval approval = approvals.lockByApprovalId(approvalId).orElseThrow();
            return action.apply(payment, approval);
        });
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }

    private record Claim(ApprovalPurchase purchase, Instant scheduled, ApprovalClosure prior) {
        static Claim owned(ApprovalPurchase purchase, Instant scheduled) { return new Claim(purchase, scheduled, null); }
        static Claim known(ApprovalClosure prior) { return new Claim(null, null, prior); }
        /** A refused closure keeps the worker's original schedule rather than inventing a new cadence. */
        Instant resumeAt(Instant at) { return scheduled == null || scheduled.isBefore(at) ? at : scheduled; }
    }

    private record Outcome(ApprovalClosureReceipt receipt, PaymentErrorCode refusal) {
        static Outcome closed(ApprovalClosureReceipt receipt) { return new Outcome(receipt, null); }
        static Outcome refused(PaymentErrorCode refusal) { return new Outcome(null, refusal); }
    }
}
