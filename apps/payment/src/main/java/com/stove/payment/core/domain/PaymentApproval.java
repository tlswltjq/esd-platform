package com.stove.payment.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_approval")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentApproval {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 36, updatable = false) private String approvalId;
    @Column(nullable = false, length = 64, updatable = false) private String paymentId;
    @Column(nullable = false, length = 36, updatable = false) private String orderNo;
    @Column(nullable = false, length = 64, updatable = false) private String requestEventId;
    @Column(nullable = false, length = 100, updatable = false) private String buyerSubject;
    @Column(nullable = false, length = 100, updatable = false) private String supplierAttemptId;
    @Column(nullable = false, length = 64, updatable = false) private String tokenFingerprint;
    @Column(nullable = false, updatable = false) private long amount;
    @Column(nullable = false, length = 3, updatable = false) private String currency;
    @Column(columnDefinition = "MEDIUMTEXT") private String protectedPayload;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private PaymentApprovalStatus status;
    @Column(nullable = false, updatable = false) private Instant receivedAt;
    @Column(nullable = false, updatable = false) private Instant expiresAt;
    private Instant admittedAt;
    private Instant firstPreparedAt;
    private Instant failedAt;
    private Instant resolvedAt;
    private Instant nextAt;
    @Column(length = 36) private String leaseToken;
    private Instant leaseUntil;
    @Column(nullable = false) private boolean postStopped;
    @Column(length = 100) private String lastErrorCode;

    private Instant lastInspectedAt;
    @Column(length = 32) private String lastInspectionError;
    public void recordInspectionFailure(String error, Instant at) {
        requireTime(at);
        if (!"INVALID_EVIDENCE".equals(error) && !"UNAVAILABLE".equals(error)) throw new IllegalArgumentException("Bounded inspection error required");
        lastInspectedAt = at; lastInspectionError = error;
    }
    private Instant reviewStartedAt;
    @Enumerated(EnumType.STRING) @Column(length = 16) private ApprovalObservation.State latestPgState;
    private Instant latestPgObservedAt;

    public void recordObservation(ApprovalObservation observation, Instant at) {
        requireTime(at);
        if (observation == null) throw new IllegalArgumentException("Observation is required");
        lastInspectedAt = at; lastInspectionError = null;
        latestPgState = observation.state();
        latestPgObservedAt = at;
    }

    public static PaymentApproval receive(Payment payment, PaymentApprovalInput input, String payload, Instant at) {
        if (payment == null || input == null || !"PORTONE_TEST".equals(payment.getProvider())
                || payment.getStatus() != PaymentStatus.PENDING || !payment.getOrderNo().equals(input.orderNo())
                || !PaymentValues.text(payload, 131072) || !PaymentValues.microsecond(at))
            throw new BusinessException(ErrorCode.CONFLICT, "Approval requires an owned pending PortOne purchase");
        PaymentApproval value = new PaymentApproval();
        value.approvalId = input.requestId(); value.paymentId = payment.getPaymentId(); value.orderNo = payment.getOrderNo();
        value.requestEventId = payment.getRequestEventId(); value.buyerSubject = payment.getBuyerSubject();
        value.supplierAttemptId = input.supplierAttemptId(); value.tokenFingerprint = input.token().fingerprint();
        value.amount = payment.getAmount(); value.currency = payment.getCurrency(); value.protectedPayload = payload;
        value.status = PaymentApprovalStatus.REQUESTED; value.receivedAt = at; value.nextAt = at;
        value.expiresAt = payment.getOrderOccurredAt().plus(Duration.ofMinutes(30));
        return value;
    }
    public ApprovalPurchase purchase() { return new ApprovalPurchase(new PgChargeRequest(paymentId, orderNo, amount, currency), supplierAttemptId); }
    public OrderAdmissionRequest admissionRequest() {
        return new OrderAdmissionRequest(approvalId, requestEventId, buyerSubject, purchase().purchase(), expiresAt);
    }
    public void requireSameInput(Payment payment, PaymentApprovalInput input) {
        if (!approvalId.equals(input.requestId()) || !paymentId.equals(payment.getPaymentId())
                || !orderNo.equals(input.orderNo()) || !buyerSubject.equals(payment.getBuyerSubject())
                || !supplierAttemptId.equals(input.supplierAttemptId()) || !tokenFingerprint.equals(input.token().fingerprint()))
            throw new BusinessException(ErrorCode.CONFLICT, "Approval command content changed");
    }
    public boolean terminal() { return status == PaymentApprovalStatus.SUCCEEDED || status == PaymentApprovalStatus.FAILED || status == PaymentApprovalStatus.EXPIRED; }
    public boolean claim(String token, Instant at, Duration lease) {
        requireLease(token, at, lease);
        if (terminal() || nextAt == null || at.isBefore(nextAt) || leased(at)) return false;
        take(token, at, lease);
        return true;
    }
    /**
     * An operator closure takes the same lease as a worker cycle, so the two never run together. It does not wait for the
     * next scheduled lookup, but it never takes over an active lease.
     */
    public boolean claimForClosure(String token, Instant at, Duration lease) {
        requireLease(token, at, lease);
        if (status != PaymentApprovalStatus.REVIEW_REQUIRED) throw new BusinessException(PaymentErrorCode.APPROVAL_NOT_HELD);
        if (leased(at)) return false;
        take(token, at, lease);
        return true;
    }
    /** Ends a held approval on re-inspected closable PG evidence. The hold reason stays as the record of why it was held. */
    public void closeAfterReview(ApprovalObservation evidence, String token, Instant at) {
        if (!owns(token, at)) throw new IllegalStateException("The closure lease is required");
        if (status != PaymentApprovalStatus.REVIEW_REQUIRED || !postStopped) throw new BusinessException(PaymentErrorCode.APPROVAL_NOT_HELD);
        if (evidence == null || !evidence.closesReview()) throw new IllegalArgumentException("Closable PG evidence is required");
        close(PaymentApprovalStatus.FAILED, at);
        leaseToken = null; leaseUntil = null;
    }
    private void requireLease(String token, Instant at, Duration lease) {
        requireTime(at);
        if (!PaymentValues.uuid(token) || lease == null || lease.isNegative() || lease.isZero() || lease.getNano() % 1000 != 0)
            throw new IllegalArgumentException("A bounded identified lease is required");
    }
    private boolean leased(Instant at) { return leaseUntil != null && at.isBefore(leaseUntil); }
    private void take(String token, Instant at, Duration lease) { leaseToken = token; leaseUntil = at.plus(lease); nextAt = leaseUntil; }
    public boolean owns(String token, Instant at) {
        return PaymentValues.microsecond(at) && token != null && token.equals(leaseToken)
                && leaseUntil != null && at.isBefore(leaseUntil);
    }
    public void admit(OrderAdmissionReceipt receipt) {
        if (receipt == null || terminal() || postStopped) throw new IllegalStateException("Approval cannot be admitted");
        receipt.requireRequest(admissionRequest());
        if (admittedAt != null && !admittedAt.equals(receipt.admittedAt()))
            throw new IllegalArgumentException("Original admission changed");
        admittedAt = receipt.admittedAt(); status = PaymentApprovalStatus.ADMITTED; lastErrorCode = null;
    }

    public void recordSaleRestriction() {
        if (status != PaymentApprovalStatus.REQUESTED || admittedAt != null || firstPreparedAt != null || postStopped)
            throw new IllegalStateException("Only an unadmitted request can wait for a sale restriction");
        lastErrorCode = "ORDER_SALE_RESTRICTED";
    }
    public void prepare(Instant at) {
        requireTime(at);
        if (admittedAt == null || terminal() || postStopped) throw new IllegalStateException("Order admission is required before dispatch");
        if (firstPreparedAt == null) firstPreparedAt = at;
    }
    public boolean canPost(Instant at) {
        return PaymentValues.microsecond(at) && admittedAt != null && firstPreparedAt != null && !postStopped && !terminal()
                && !at.isBefore(firstPreparedAt) && at.isBefore(firstPreparedAt.plus(Duration.ofHours(3)))
                && leaseUntil != null && at.isBefore(leaseUntil);
    }
    public boolean finish(String token, Instant at, Instant next) {
        if (!owns(token, at)) return false;
        if (!terminal() && (!PaymentValues.microsecond(next) || next.isBefore(at)))
            throw new IllegalArgumentException("A durable next check is required");
        nextAt = terminal() ? null : next; leaseToken = null; leaseUntil = null;
        return true;
    }
    public void stopPosts(String reason) { stopPosts(reason, Payment.now()); }
    public void stopPosts(String reason, Instant at) {
        requireTime(at);
        if (terminal() || !PaymentValues.text(reason, 100)) throw new IllegalArgumentException("A current stop reason is required");
        if (status != PaymentApprovalStatus.REVIEW_REQUIRED && reviewStartedAt == null) reviewStartedAt = at;
        postStopped = true;
        // A later lookup error must not hide an already observed financial anomaly.
        if (!"PAID_WITHOUT_ADMISSION".equals(lastErrorCode)) lastErrorCode = reason;
        status = PaymentApprovalStatus.REVIEW_REQUIRED;
    }
    public boolean expireAfterVerifiedReady(ApprovalObservation observation, Instant at) {
        if (observation == null || observation.state() != ApprovalObservation.State.READY
                || status != PaymentApprovalStatus.REVIEW_REQUIRED || !postStopped
                || !"PG_INVALID_EVIDENCE".equals(lastErrorCode)
                || admittedAt != null || firstPreparedAt != null || failedAt != null) return false;
        expire(at);
        return true;
    }
    public void recordFailure(Instant observedFailureAt) {
        if (terminal() || !PaymentValues.microsecond(observedFailureAt)) throw new IllegalArgumentException("Verified attempt failure is required");
        if (failedAt != null && !failedAt.equals(observedFailureAt)) throw new IllegalArgumentException("Failure evidence changed");
        failedAt = observedFailureAt; postStopped = true; status = PaymentApprovalStatus.FAILURE_CONFIRMED;
    }
    public void failBeforeAdmission(Instant failedAt, Instant at) {
        if (admittedAt != null) throw new IllegalStateException("Order failure notification is required");
        recordFailure(failedAt); close(PaymentApprovalStatus.FAILED, at);
    }
    public void expire(Instant at) {
        if (admittedAt != null) throw new IllegalStateException("An admitted payment cannot expire on time alone");
        close(PaymentApprovalStatus.EXPIRED, at);
    }
    public void succeed(Payment payment, Instant at) {
        if (admittedAt == null || payment.getStatus() != PaymentStatus.PAID || !paymentId.equals(payment.getPaymentId())
                || amount != payment.getAmount() || !currency.equals(payment.getCurrency()))
            throw new IllegalArgumentException("A matching committed paid payment is required");
        close(PaymentApprovalStatus.SUCCEEDED, at);
    }
    public void fail(OrderAdmissionFailureReceipt receipt, Instant at) {
        if (admittedAt == null || failedAt == null) throw new IllegalStateException("An admitted failed attempt is required");
        receipt.requireRequest(new OrderAdmissionFailure(admissionRequest(), supplierAttemptId, failedAt));
        close(PaymentApprovalStatus.FAILED, at);
    }
    private void close(PaymentApprovalStatus state, Instant at) {
        if (terminal()) {
            if (status == state) return;
            throw new IllegalStateException("A resolved approval is immutable");
        }
        requireTime(at); status = state; resolvedAt = at; protectedPayload = null; nextAt = null;
    }
    private void requireTime(Instant at) {
        if (!PaymentValues.microsecond(at) || at.isBefore(receivedAt)) throw new IllegalArgumentException("An ordered microsecond time is required");
    }
    @Override public String toString() { return "PaymentApproval[id=" + approvalId + ",status=" + status + "]"; }
}
