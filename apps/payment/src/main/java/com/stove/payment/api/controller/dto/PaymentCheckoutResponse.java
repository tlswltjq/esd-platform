package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.PaymentCheckout;
import java.util.List;

/** PortOne browser SDK parameters; server credentials never enter this response. */
public record PaymentCheckoutResponse(String storeId, String channelKey, String paymentId, String orderName,
        long totalAmount, String currency, String payMethod, OrderReference customData, List<String> noticeUrls) {
    public record OrderReference(String orderNo) { }

    public static PaymentCheckoutResponse from(PaymentCheckout checkout) {
        return new PaymentCheckoutResponse(checkout.storeId(), checkout.channelKey(), checkout.paymentId(), checkout.title(),
                checkout.amount(), "CURRENCY_" + checkout.currency(), "EASY_PAY", new OrderReference(checkout.orderNo()),
                List.of(checkout.webhookUrl().toString()));
    }
}
