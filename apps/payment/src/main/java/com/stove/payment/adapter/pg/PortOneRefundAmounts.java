package com.stove.payment.adapter.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.stove.payment.core.domain.PgPaymentAmounts;
import com.stove.payment.core.domain.PgRefundLookupRequest;

/** Compares provider amounts with the immutable approval, without filling unknown stored values. */
final class PortOneRefundAmounts {
    private PortOneRefundAmounts() { }

    static void requireOriginal(PgRefundLookupRequest purchase, JsonNode amount) {
        PgPaymentAmounts observed = new PgPaymentAmounts(number(amount, "paid"), number(amount, "discount"));
        observed.requireTotal(purchase.amount());
        if (number(amount, "total") != purchase.amount()
                || (purchase.paymentAmounts() != null && !purchase.paymentAmounts().equals(observed))
                || (purchase.paymentAmounts() == null && observed.discountAmount() != 0))
            throw new IllegalArgumentException("Refund amounts do not match the original approval evidence");
    }

    static Long reportedPoints(JsonNode cancellation) {
        return cancellation.has("easyPayDiscountAmount") ? number(cancellation, "easyPayDiscountAmount") : null;
    }

    private static long number(JsonNode value, String name) {
        JsonNode field = value.path(name);
        if (!field.isIntegralNumber() || !field.canConvertToLong())
            throw new IllegalArgumentException("Invalid supplier amount");
        return field.longValue();
    }
}
