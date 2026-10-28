package com.stove.order.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundApprovalStop {
    @Column(name="stop_request_id", unique=true, length=36) private String requestId;
    @Column(name="stop_operator_subject", length=100) private String operatorSubject;
    @Column(name="stop_reason", length=500) private String reason;
    @Column(name="stop_received_at") private Instant receivedAt;
    @Column(name="stop_suspension_request_id", unique=true, length=64) private String suspensionRequestId;
    @Column(name="stop_suspension_event_id", unique=true, length=64) private String suspensionEventId;
    @Column(name="stop_suspension_fingerprint", length=64) private String suspensionFingerprint;
    @Column(name="stop_license_id", length=36) private String licenseId;
    @Column(name="stop_restoration_request_id", unique=true, length=64) private String restorationRequestId;
    @Column(name="stop_restoration_requested_at") private Instant restorationRequestedAt;
    @Column(name="stop_restored_event_id", unique=true, length=64) private String restoredEventId;
    @Column(name="stop_restored_fingerprint", length=64) private String restoredFingerprint;
    @Column(name="stop_restored_at") private Instant restoredAt;
    @Column(name="stop_reopened_at") private Instant reopenedAt;
    @Column(name="stop_next_review_id", unique=true, length=36) private String nextReviewId;

    static RefundApprovalStop receive(RefundApprovalStopRequest input, String suspension, Instant at) {
        RefundReviewValues.requireTime(at);
        RefundApprovalStop stop = new RefundApprovalStop(); stop.requestId=input.requestId(); stop.operatorSubject=input.operatorSubject();
        stop.reason=input.reason(); stop.receivedAt=at; stop.suspensionRequestId=suspension; return stop;
    }
    public void requireSame(RefundApprovalStopRequest input, RefundReviewRound round) {
        if (!requestId.equals(input.requestId()) || !round.getRefundId().equals(input.refundId()) || !round.getReviewId().equals(input.reviewId())
                || !operatorSubject.equals(input.operatorSubject()) || !reason.equals(input.reason())) throw conflict();
    }
    public boolean observeSuspension(LicenseSuspensionConfirmation input, Instant at) {
        String fingerprint=RefundRestorationValues.fingerprint(input.eventId(),input.occurredAt().toString(),input.requestEventId(),
                input.refundId(),input.licenseId(),input.orderNo(),input.buyerSubject(),input.productId().toString(),input.productCode(),input.paymentId());
        if (suspensionEventId!=null) {
            if (!suspensionFingerprint.equals(fingerprint)) throw new RefundCompletionRejected("중단 대상 정지 근거가 다릅니다.");
            return false;
        }
        RefundReviewValues.requireTime(at);
        if (!input.requestEventId().equals(suspensionRequestId) || reopenedAt!=null || at.isBefore(receivedAt) || at.isBefore(input.occurredAt()))
            throw new RefundCompletionRejected("중단 대상 정지를 기다리고 있지 않습니다.");
        suspensionEventId=input.eventId(); suspensionFingerprint=fingerprint; licenseId=input.licenseId();
        restorationRequestId=UUID.randomUUID().toString(); restorationRequestedAt=at; return true;
    }
    public boolean observeRestored(ApprovalCorrectionConfirmation input) {
        if (!input.stopRequestId().equals(requestId) || !input.requestEventId().equals(restorationRequestId)
                || !input.licenseId().equals(licenseId) || !input.suspensionRequestEventId().equals(suspensionRequestId)
                || !input.suspensionEventId().equals(suspensionEventId) || input.occurredAt().isBefore(restorationRequestedAt))
            throw new RefundCompletionRejected("승인 정정 복구 근거가 다릅니다.");
        if (restoredEventId!=null) {
            if (!restoredFingerprint.equals(input.fingerprint())) throw new RefundCompletionRejected("복구 재전송 본문이 다릅니다.");
            return false;
        }
        restoredEventId=input.eventId(); restoredFingerprint=input.fingerprint(); restoredAt=input.occurredAt(); return true;
    }
    public boolean canReopen() { return reopenedAt==null && (suspensionRequestId==null || restoredEventId!=null); }
    public void reopen(String next, Instant at) {
        RefundReviewValues.requireReviewId(next); RefundReviewValues.requireTime(at);
        if (!canReopen() || at.isBefore(receivedAt) || (restoredAt!=null && at.isBefore(restoredAt))) throw conflict();
        nextReviewId=next; reopenedAt=at;
    }
    public RefundApprovalStopReceipt receipt(String refund, String review) {
        return new RefundApprovalStopReceipt(requestId,refund,review,operatorSubject,reason,receivedAt);
    }
    public RefundApprovalStopView view(String refund, String review) {
        return new RefundApprovalStopView(receipt(refund,review),restorationRequestedAt,restoredAt,reopenedAt,nextReviewId);
    }
    private static BusinessException conflict() { return new BusinessException(ErrorCode.CONFLICT,"이미 저장된 승인 중단과 다릅니다."); }
}
