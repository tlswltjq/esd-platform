package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.RefundReviewView;
import com.stove.order.core.domain.RefundStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record AdminRefundResponse(String refundId, String orderNo, String buyerSubject, String productCode, String title,
        long amount, String currency, Instant paymentConfirmedAt, Instant receivedAt,
        RefundStatus status, AdminRefundDecisionResponse review, AdminRefundReviewResponse currentReview, RefundAmountDetailsResponse amountDetails,
        @Schema(description = "이번 지급 확인(PENDING_LICENSE_GRANT) 대기에 들어간 시각(결정 125). 대기를 벗어나도 남는다. 한 번도 들어가지 않았으면 null", nullable = true)
        Instant licenseGrantWaitStartedAt,
        @Schema(description = "이번 정지(PENDING_LICENSE_SUSPENSION) 대기에 들어간 시각(지급 확인·재시도). 대기를 벗어나도 남는다. 한 번도 들어가지 않았거나 이 시각을 기록하기 전에 대기를 벗어났으면 null", nullable = true)
        Instant licenseSuspensionWaitStartedAt,
        @Schema(description = "이번 복구(PENDING_LICENSE_RESTORATION) 대기에 들어간 시각(PG 실패 확정). 대기를 벗어나도 남는다. 한 번도 들어가지 않았거나 이 시각을 기록하기 전에 대기를 벗어났으면 null", nullable = true)
        Instant licenseRestorationWaitStartedAt,
        @Schema(description = "이번 승인 중단(PENDING_APPROVAL_STOP) 대기에 들어간 시각. 대기를 벗어나도 남는다. 한 번도 들어가지 않았거나 이 시각을 기록하기 전에 대기를 벗어났으면 null", nullable = true)
        Instant approvalStopWaitStartedAt) {
    public static AdminRefundResponse from(RefundReviewView view) {
        return new AdminRefundResponse(view.refundId(), view.orderNo(), view.buyerSubject(), view.productCode(), view.title(),
                view.amount(), view.currency(), view.paymentConfirmedAt(), view.receivedAt(), view.status(),
                AdminRefundDecisionResponse.from(view.review()), AdminRefundReviewResponse.from(view.currentReview()), RefundAmountDetailsResponse.from(view.amountDetails()), view.licenseGrantWaitStartedAt(),
                view.licenseSuspensionWaitStartedAt(), view.licenseRestorationWaitStartedAt(), view.approvalStopWaitStartedAt());
    }
}
