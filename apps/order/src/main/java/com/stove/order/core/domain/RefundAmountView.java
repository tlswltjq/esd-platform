package com.stove.order.core.domain;

import java.time.Instant;

public record RefundAmountView(long orderAmount, Long pgPaidAmount, Long pgDiscountAmount,
        Long confirmedCancellationAmount, Long confirmedPointRefundAmount, Instant reportedAt, String source) {
    public static RefundAmountView unknown(long orderAmount) {
        return new RefundAmountView(orderAmount, null, null, null, null, null, null);
    }

    public static RefundAmountView from(Order order, Refund refund) {
        RefundAmountProjection projection = refund.getAmountProjection();
        Long paid = order.getPgPaidAmount(); Long discount = order.getPgDiscountAmount();
        if (projection == null) return new RefundAmountView(order.getAmount(), paid, discount, null, null, null, null);
        if (paid == null) { paid = projection.getRefundPgPaidAmount(); discount = projection.getRefundPgDiscountAmount(); }
        return new RefundAmountView(order.getAmount(), paid, discount, projection.getConfirmedCancellationAmount(),
                projection.getConfirmedPointRefundAmount(), projection.getRefundAmountsReportedAt(), projection.getRefundAmountsSource());
    }
}
