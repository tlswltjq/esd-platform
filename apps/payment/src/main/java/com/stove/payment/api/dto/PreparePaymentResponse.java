package com.stove.payment.api.dto;

public record PreparePaymentResponse(String orderNo, String pgTxId, long amount, String currency, String redirectUrl) {
}
