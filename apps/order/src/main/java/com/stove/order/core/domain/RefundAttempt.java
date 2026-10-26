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
