package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.RefundReviewView;
import com.stove.order.core.domain.RefundStatus;
import java.time.Instant;

public record AdminRefundResponse(String refundId, String orderNo, String buyerSubject, String productCode, String title,
        long amount, String currency, Instant paymentConfirmedAt, Instant receivedAt,
        RefundStatus status, AdminRefundDecisionResponse review, AdminRefundReviewResponse currentReview) {
    public static AdminRefundResponse from(RefundReviewView view) {
        return new AdminRefundResponse(view.refundId(), view.orderNo(), view.buyerSubject(), view.productCode(), view.title(),
                view.amount(), view.currency(), view.paymentConfirmedAt(), view.receivedAt(), view.status(),
                AdminRefundDecisionResponse.from(view.review()), AdminRefundReviewResponse.from(view.currentReview()));
    }
}
