package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.PaymentPreparation;

public record PreparePaymentResponse(String orderNo, String pgTxId, long amount, String currency, String redirectUrl) {

    public static PreparePaymentResponse from(PaymentPreparation preparation) {
        return new PreparePaymentResponse(preparation.orderNo(), preparation.pgTxId(),
                preparation.amount(), preparation.currency(), preparation.redirectUrl());
    }
}
