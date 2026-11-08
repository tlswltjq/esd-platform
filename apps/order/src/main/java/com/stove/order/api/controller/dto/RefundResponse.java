package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.RefundView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record RefundResponse(String refundId, String orderNo, long amount, String currency,
        Status status, Instant receivedAt, Instant completedAt, String rejectionReason,
        @Schema(description = "확정 실패와 실제 이용권 복구가 끝나 재시도 가능한 현재 시도 ID. 그 외 상태는 null", format = "uuid", nullable = true)
        String retryableAttemptId, CustomerRefundReviewResponse currentReview, Instant reconsiderationDeadlineAt, boolean reconsiderationAvailable,
        @Schema(description="현재 환불 결과 확인이 지연되거나(RESULT_CONFIRMATION_DELAYED) 환불 처리 준비가 지연될 때(LICENSE_GRANT_CONFIRMATION_DELAYED) 제공하는 안내. 이외 상태와 준비 정보 미수신 시 null이며, 실패·완료나 재신청 허용을 뜻하지 않습니다.",nullable=true)
        RefundProcessingNoticeResponse processingNotice, RefundAmountDetailsResponse amountDetails,
        @Schema(description = "환불 확정 실패로 복구할 때 같은 상품의 다른 보유권이 이미 쓰이고 있어 이 구매분이 보관함으로 돌아갔으면 true")
        boolean purchaseReturnedToStorage,
        @Schema(description = "환불이 FAILED일 때만 제공하는 고객용 실패 사유 코드와 재신청 안내(결정 134). 재신청은 retryableAttemptId로 POST /api/v1/orders/{orderNo}/refund/retries. 그 외 상태는 null", nullable = true)
        RefundFailureNoticeResponse failureNotice) {
    public enum Status { PENDING_REVIEW, PROCESSING, COMPLETED, FAILED, REJECTED }

    public static RefundResponse from(RefundView view) {
        Status status = switch (view.status()) {
            case PENDING_REVIEW -> Status.PENDING_REVIEW;
            case COMPLETED -> Status.COMPLETED;
            case FAILED -> Status.FAILED;
            case REJECTED -> Status.REJECTED;
            case PENDING_APPROVAL_STOP, PENDING_LICENSE_GRANT, PENDING_LICENSE_SUSPENSION, PENDING_PAYMENT_REFUND, PENDING_LICENSE_RESTORATION -> Status.PROCESSING;
        };
        return new RefundResponse(view.refundId(), view.orderNo(), view.amount(), view.currency(), status, view.receivedAt(), view.completedAt(), view.rejectionReason(),
                view.retryableAttemptId(), CustomerRefundReviewResponse.from(view.currentReview()), view.reconsiderationDeadlineAt(), view.reconsiderationAvailable(),
                notice(view), RefundAmountDetailsResponse.from(view.amountDetails()),
                view.purchaseReturnedToStorage(), RefundFailureNoticeResponse.from(view.failureReason()));
    }

    /** 두 안내는 서로 다른 상태(PENDING_PAYMENT_REFUND / PENDING_LICENSE_GRANT)에만 생겨 함께 오지 않는다. */
    private static RefundProcessingNoticeResponse notice(RefundView view) {
        RefundProcessingNoticeResponse payment = RefundProcessingNoticeResponse.delayed(view.confirmationDelayedSince());
        return payment != null ? payment : RefundProcessingNoticeResponse.licenseGrantDelayed(view.licenseGrantDelayedSince());
    }
}
