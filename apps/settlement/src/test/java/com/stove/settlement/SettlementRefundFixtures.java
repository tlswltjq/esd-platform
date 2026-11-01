package com.stove.settlement;

import static com.stove.settlement.SettlementFixtures.NOW;
import static com.stove.settlement.SettlementFixtures.id;

import com.stove.settlement.core.domain.ConfirmedRefund;
import com.stove.settlement.core.domain.PaidOrder;
import com.stove.settlement.core.domain.PgPayment;
import com.stove.settlement.core.domain.PgRefund;
import com.stove.settlement.core.domain.PgRefundCancellation;
import com.stove.common.kafka.contract.payload.RefundPaymentConfirmedEvent;
import java.util.List;

public final class SettlementRefundFixtures {
    private SettlementRefundFixtures() { }
    public static ConfirmedRefund confirmation(PaidOrder order, PgPayment payment) {
        return new ConfirmedRefund(id(), NOW.minusSeconds(2), id(), id(), id(), order.orderNo(), order.paymentId(),
                order.buyerSubject(), order.amount(), order.currency(), "PG_RESPONSE", payment.evidence().transactionId(), List.of(id()));
    }
    public static PgRefund proof(PgPayment payment, ConfirmedRefund input) {
        return new PgRefund(payment, List.of(new PgRefundCancellation(payment.evidence().provider(), input.cancellationIds().getFirst(),
                payment.amount(), input.occurredAt().minusSeconds(1))), input.occurredAt().minusSeconds(1));
    }
    public static RefundPaymentConfirmedEvent event(ConfirmedRefund input) {
        return new RefundPaymentConfirmedEvent(input.eventId(),input.occurredAt(),input.refundId(),input.attemptId(),input.requestEventId(),
                input.orderNo(),input.paymentId(),input.buyerSubject(),input.amount(),input.currency(),input.source(),input.providerTransactionId(),input.cancellationIds());
    }
}
