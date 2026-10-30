package com.stove.order.core.domain;

/** Supplier approval amounts, independent of order discounts and actual refund settlement. */
public record ProviderPaymentAmounts(long paidAmount, long discountAmount) {
    public ProviderPaymentAmounts {
        if (paidAmount < 0 || discountAmount < 0) throw new RefundCompletionRejected("PG 승인 금액이 올바르지 않습니다.");
    }

    public void requireTotal(long total) {
        if (total <= 0 || paidAmount > total || discountAmount != total - paidAmount)
            throw new RefundCompletionRejected("PG 승인 금액과 주문 총액이 다릅니다.");
    }

    public static ProviderPaymentAmounts fromNullable(Long paid, Long discount) {
        if (paid == null && discount == null) return null;
        if (paid == null || discount == null) throw new RefundCompletionRejected("PG 승인 금액 구성의 일부가 없습니다.");
        return new ProviderPaymentAmounts(paid, discount);
    }
}
