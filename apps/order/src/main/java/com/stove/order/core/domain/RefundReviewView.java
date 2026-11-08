package com.stove.order.core.domain;

import java.time.Instant;

public record RefundReviewView(String refundId, String orderNo, String buyerSubject, String productCode,
        String title, long amount, String currency, Instant paymentConfirmedAt, Instant receivedAt,
        RefundStatus status, RefundReviewReceipt review, RefundReviewRoundView currentReview, RefundAmountView amountDetails,
        Instant licenseGrantWaitStartedAt, Instant licenseSuspensionWaitStartedAt, Instant licenseRestorationWaitStartedAt,
        Instant approvalStopWaitStartedAt) {
    public RefundReviewView(String refundId, String orderNo, String buyerSubject, String productCode,
            String title, long amount, String currency, Instant paymentConfirmedAt, Instant receivedAt,
            RefundStatus status, RefundReviewReceipt review, RefundReviewRoundView currentReview) {
        this(refundId, orderNo, buyerSubject, productCode, title, amount, currency, paymentConfirmedAt, receivedAt,
                status, review, currentReview, RefundAmountView.unknown(amount), null, null, null, null);
    }
}
