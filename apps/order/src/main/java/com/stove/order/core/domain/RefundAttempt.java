package com.stove.order.core.domain;

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
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** An execution history record. Creating it does not authorize a PG call or a new retry. */
@Entity
@Table(name = "order_refund_attempt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundAttempt {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, updatable = false, length = 36)
    private String attemptId;
    @Column(nullable = false, updatable = false, length = 36)
    private String refundId;
    @Column(nullable = false, updatable = false)
    private int attemptNo;
    @Column(nullable = false, updatable = false)
    private Instant startedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private RefundAttemptStatus status;
    private Instant resolvedAt;
    @Column(length = 100)
    private String failureReasonCode;
    @Column(length = 500)
    private String failureReason;
    @Column(unique = true, length = 64)
    private String suspensionRequestEventId;
    @Column(unique = true, length = 64)
    private String suspensionEventId;
    private Instant suspensionOccurredAt;
    @Column(length = 36)
    private String licenseId;
    @Column(unique = true, length = 64)
    private String executionRequestEventId;
    @Column(nullable = false)
    private boolean reconciliationRequired;
    @Column(unique = true, length = 64)
    private String successEventId;
    @Column(length = 64)
    private String successConfirmationEventId;
    @Column(length = 100)
    private String successfulCancellationId;
    @Column(length = 64)
    private String successFingerprint;

    @Column(unique = true, length = 64) private String failureEventId;
    @Column(length = 64) private String failureFingerprint;
    @Column(unique = true, length = 64) private String restorationRequestEventId;
    private Instant restorationRequestedAt;
    @Column(unique = true, length = 64) private String restoredEventId;
    private Instant restoredAt;
    @Column(length = 64) private String restorationFingerprint;

    public void recordRefundFailure(RefundFailureConfirmation input, Instant at) {
        if (!RefundRestorationValues.time(at) || at.isBefore(input.occurredAt()) || status != RefundAttemptStatus.PENDING)
            throw new RefundCompletionRejected("미취소 실패를 기다리는 시도가 아닙니다.");
        fail(input.occurredAt(), "PG_CANCELLATION_FAILED", "PG 취소 실패와 원결제 미취소를 확인했습니다.");
        failureEventId = input.eventId(); failureFingerprint = input.fingerprint();
        restorationRequestEventId = UUID.randomUUID().toString(); restorationRequestedAt = at;
        reconciliationRequired = false;
    }

    public boolean recordRestoration(LicenseRestorationConfirmation input) {
        if (status != RefundAttemptStatus.FAILED || !Objects.equals(restorationRequestEventId, input.requestEventId())
                || !Objects.equals(failureEventId, input.failureEventId()) || !Objects.equals(licenseId, input.licenseId())
                || !Objects.equals(suspensionRequestEventId, input.suspensionRequestEventId())
                || !Objects.equals(suspensionEventId, input.suspensionEventId()) || input.occurredAt().isBefore(restorationRequestedAt))
            throw new RefundCompletionRejected("복구 완료가 실패·복구 요청·실제 정지와 다릅니다.");
        if (restoredEventId != null) {
            if (!Objects.equals(restorationFingerprint, input.fingerprint())) throw new RefundCompletionRejected("확정한 복구 완료와 다릅니다.");
            return false;
        }
        restoredEventId = input.eventId(); restoredAt = input.occurredAt(); restorationFingerprint = input.fingerprint();
        return true;
    }

    public boolean recordCancellationSuccess(RefundExecutionSuccess input) {
        if (input == null || !attemptId.equals(input.execution().attemptId()) || !refundId.equals(input.execution().refundId())
                || !Objects.equals(executionRequestEventId, input.execution().requestEventId())
                || input.execution().occurredAt().isBefore(startedAt)) throw new RefundCompletionRejected("실행 성공의 요청 근거가 다릅니다.");
        RefundPaymentConfirmation proof = input.execution();
        if (status != RefundAttemptStatus.PENDING) {
            if (status == RefundAttemptStatus.SUCCEEDED && Objects.equals(successEventId, proof.eventId())
                    && Objects.equals(resolvedAt, proof.occurredAt()) && Objects.equals(successConfirmationEventId, input.confirmationEventId())
                    && Objects.equals(successfulCancellationId, proof.cancellationIds().get(0))
                    && Objects.equals(successFingerprint, proof.fingerprint())) return false;
            throw new RefundCompletionRejected("이미 확정한 실행 성공 근거가 다릅니다.");
        }
        succeed(proof.occurredAt()); successEventId = proof.eventId(); successConfirmationEventId = input.confirmationEventId();
        successfulCancellationId = proof.cancellationIds().get(0); successFingerprint = proof.fingerprint();
        reconciliationRequired = false;
        return true;
    }

    /** Completion of the customer's refund does not establish the outcome of this execution. */
    public void requireReconciliation() {
        if (status != RefundAttemptStatus.PENDING) throw new RefundCompletionRejected("이미 확정된 실행의 결과와 다릅니다.");
        reconciliationRequired = true;
    }

    @Column(length = 36, unique = true) private String previousAttemptId;
    private Instant retryRequestedAt;
    @Column(length = 64, unique = true) private String retrySuspensionRequestEventId;

    public void requireRetryableAt(Instant at) {
        if (!RefundRestorationValues.time(at) || status != RefundAttemptStatus.FAILED || failureEventId == null
                || restoredEventId == null || restoredAt == null || at.isBefore(restoredAt))
            throw new BusinessException(ErrorCode.CONFLICT, "확인된 실패와 실제 권한 복구 완료가 필요합니다.");
    }

    public void registerRetry(Instant at) {
        requireRetryableAt(at);
        if (retryRequestedAt != null) return;
        retryRequestedAt = at; retrySuspensionRequestEventId = UUID.randomUUID().toString();
    }

    public static RefundAttempt afterRetrySuspension(Refund refund, RefundAttempt previous, LicenseSuspensionConfirmation input, Instant at) {
        if (refund == null || previous == null || input == null) throw new RefundCompletionRejected("재실행과 실제 정지 근거가 필요합니다.");
        refund.verifySuspensionRequest(input); previous.requireRetryableAt(at);
        if (refund.getStatus() != RefundStatus.PENDING_LICENSE_SUSPENSION || !previous.getAttemptId().equals(refund.getCurrentAttemptId())
                || !previous.getRefundId().equals(refund.getRefundId()) || previous.getRetryRequestedAt() == null
                || !input.requestEventId().equals(previous.getRetrySuspensionRequestEventId())
                || !input.licenseId().equals(previous.getLicenseId()) || input.occurredAt().isBefore(previous.getRetryRequestedAt())
                || at.isBefore(input.occurredAt())) throw new RefundCompletionRejected("새 실행에 해당하는 재접수·실제 정지가 다릅니다.");
        RefundAttempt attempt = start(refund.getRefundId(), Math.addExact(previous.getAttemptNo(), 1), at);
        attempt.previousAttemptId = previous.getAttemptId(); attempt.suspensionRequestEventId = input.requestEventId();
        attempt.suspensionEventId = input.eventId(); attempt.suspensionOccurredAt = input.occurredAt(); attempt.licenseId = input.licenseId();
        attempt.executionRequestEventId = UUID.randomUUID().toString(); return attempt;
    }

    public static RefundAttempt afterSuspension(Refund refund, LicenseSuspensionConfirmation input, Instant startedAt) {
        if (refund == null) throw new RefundCompletionRejected("환불 접수 근거가 필요합니다.");
        refund.verifySuspensionRequest(input);
        if (refund.getStatus() != RefundStatus.PENDING_LICENSE_SUSPENSION || refund.getCurrentAttemptId() != null) {
            throw new RefundCompletionRejected("정지 확인을 기다리는 환불이 아닙니다.");
        }
        RefundAttempt attempt = start(refund.getRefundId(), 1, startedAt);
        attempt.suspensionRequestEventId = input.requestEventId();
        attempt.suspensionEventId = input.eventId();
        attempt.suspensionOccurredAt = input.occurredAt();
        attempt.licenseId = input.licenseId();
        attempt.executionRequestEventId = UUID.randomUUID().toString();
        return attempt;
    }

    public void verifySuspension(LicenseSuspensionConfirmation input) {
        if (input == null || !refundId.equals(input.refundId()) || suspensionEventId == null
                || !suspensionEventId.equals(input.eventId())
                || !Objects.equals(suspensionRequestEventId, input.requestEventId())
                || !Objects.equals(suspensionOccurredAt, input.occurredAt())
                || !Objects.equals(licenseId, input.licenseId()) || executionRequestEventId == null) {
            throw new RefundCompletionRejected("이미 실행한 정지 완료 근거와 다릅니다.");
        }
    }

    public static RefundAttempt start(String refundId, int attemptNo, Instant startedAt) {
        require(refundId != null && refundId.matches(OrderValues.UUID_PATTERN), "환불 식별자가 올바르지 않습니다.");
        require(attemptNo > 0, "실행 순번은 양수여야 합니다.");
        requireRecordedTime(startedAt);
        RefundAttempt attempt = new RefundAttempt();
        attempt.attemptId = UUID.randomUUID().toString();
        attempt.refundId = refundId;
        attempt.attemptNo = attemptNo;
        attempt.startedAt = startedAt;
        attempt.status = RefundAttemptStatus.PENDING;
        return attempt;
    }

    public boolean succeed(Instant resolvedAt) {
        requireRecordedTime(resolvedAt);
        return resolve(RefundAttemptStatus.SUCCEEDED, resolvedAt, null, null);
    }

    public boolean fail(Instant resolvedAt, String code, String reason) {
        requireRecordedTime(resolvedAt);
        require(text(code, 100) && text(reason, 500), "확정 실패의 사유가 올바르지 않습니다.");
        return resolve(RefundAttemptStatus.FAILED, resolvedAt, code, reason);
    }

    private boolean resolve(RefundAttemptStatus next, Instant resolvedAt, String code, String reason) {
        if (status != RefundAttemptStatus.PENDING) {
            if (status == next && Objects.equals(this.resolvedAt, resolvedAt)
                    && Objects.equals(failureReasonCode, code) && Objects.equals(failureReason, reason)) return false;
            throw new BusinessException(ErrorCode.CONFLICT, "이미 확정한 실행 결과와 다릅니다.");
        }
        status = next;
        this.resolvedAt = resolvedAt;
        failureReasonCode = code;
        failureReason = reason;
        return true;
    }

    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }

    private static void requireRecordedTime(Instant value) {
        require(value != null && value.getNano() % 1_000 == 0, "실행 시각은 마이크로초 정밀도로 기록해야 합니다.");
    }

    private static void require(boolean valid, String reason) {
        if (!valid) throw new BusinessException(ErrorCode.INVALID_REQUEST, reason);
    }
}
