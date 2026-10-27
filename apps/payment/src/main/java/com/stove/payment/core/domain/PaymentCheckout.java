package com.stove.payment.core.domain;

import java.net.URI;

/** Public checkout values read from one existing purchase; no provider credentials. */
public record PaymentCheckout(String paymentId, String orderNo, String title, long amount, String currency,
        String storeId, String channelKey, URI webhookUrl) {
    public PaymentCheckout {
        if (!PaymentValues.text(paymentId, 64) || !PaymentValues.uuid(orderNo) || !PaymentValues.text(title, 200)
                || amount <= 0 || !"KRW".equals(currency) || !PaymentValues.text(storeId, 64)
                || !PaymentValues.text(channelKey, 100) || webhookUrl == null || !"https".equals(webhookUrl.getScheme())
                || webhookUrl.getHost() == null || webhookUrl.getUserInfo() != null
                || webhookUrl.getQuery() != null || webhookUrl.getFragment() != null) {
            throw new IllegalArgumentException("Valid purchase and public checkout settings are required");
        }
    }
}
