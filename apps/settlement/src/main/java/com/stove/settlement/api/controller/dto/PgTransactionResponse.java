package com.stove.settlement.api.controller.dto;

import com.stove.settlement.core.domain.PgCost;
import com.stove.settlement.core.domain.PgEvidence;
import com.stove.settlement.core.domain.PgPayment;
import com.stove.settlement.core.domain.PgTransactionView;
import java.time.Instant;

public record PgTransactionResponse(String paymentId, String orderNo, long amount, String currency,
        PgEvidence transaction, String pgCostStatus, PgCost pgCost, Instant receivedAt, Instant costReceivedAt) {
    public static PgTransactionResponse from(PgTransactionView view) {
        PgPayment payment = view.payment();
        return new PgTransactionResponse(payment.paymentId(), payment.orderNo(), payment.amount(), payment.currency(),
                payment.evidence(), payment.cost() == null ? "UNCONFIRMED" : "CONFIRMED", payment.cost(),
                view.receivedAt(), view.costReceivedAt());
    }
}
