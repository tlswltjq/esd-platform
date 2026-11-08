package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.RefundReviewView;
import com.stove.order.core.domain.RefundStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record AdminRefundResponse(String refundId, String orderNo, String buyerSubject, String productCode, String title,
        long amount, String currency, Instant paymentConfirmedAt, Instant receivedAt,
        RefundStatus status, AdminRefundDecisionResponse review, AdminRefundReviewResponse currentReview, RefundAmountDetailsResponse amountDetails,
        @Schema(description = "이번 지급 확인(PENDING_LICENSE_GRANT) 대기에 들어간 시각(결정 125). 대기를 벗어나도 남는다. 한 번도 들어가지 않았으면 null", nullable = true)
        Instant licenseGrantWaitStartedAt) {
    public static AdminRefundResponse from(RefundReviewView view) {
        return new AdminRefundResponse(view.refundId(), view.orderNo(), view.buyerSubject(), view.productCode(), view.title(),
                view.amount(), view.currency(), view.paymentConfirmedAt(), view.receivedAt(), view.status(),
                AdminRefundDecisionResponse.from(view.review()), AdminRefundReviewResponse.from(view.currentReview()), RefundAmountDetailsResponse.from(view.amountDetails()), view.licenseGrantWaitStartedAt());
    }
}
