package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.SimulatedPgTransaction;

public record SimulatorCheckoutResponse(
        String orderNo, String pgTxId, long amount, String currency,
        SimulatedPgTransaction.Status status
) {
    public static SimulatorCheckoutResponse from(SimulatedPgTransaction transaction) {
        return new SimulatorCheckoutResponse(transaction.getOrderNo(), transaction.getPgTxId(),
                transaction.getAmount(), transaction.getCurrency(), transaction.getStatus());
    }
}
