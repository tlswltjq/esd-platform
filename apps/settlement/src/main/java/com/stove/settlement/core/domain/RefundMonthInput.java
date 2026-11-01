package com.stove.settlement.core.domain;

import java.time.Instant;

/** Closing-state snapshots must be obtained under the posting service's transaction and locks. */
public record RefundMonthInput(String refundId, String orderNo, Instant confirmedAt, Instant receivedAt,
        boolean confirmationMonthClosed, boolean currentMonthClosed, RefundMonthSelection recorded) {
    public RefundMonthInput {
        SettlementValues.require(SettlementValues.uuid(refundId) && SettlementValues.uuid(orderNo), "환불·원주문 식별자가 필요합니다.");
        SettlementValues.require(SettlementValues.microsecond(confirmedAt) && SettlementValues.microsecond(receivedAt)
                && !receivedAt.isBefore(confirmedAt), "환불 확인·접수 시각이 올바르지 않습니다.");
        String confirmationMonth = SettlementMonth.at(confirmedAt).value();
        String currentMonth = SettlementMonth.at(receivedAt).value();
        if (recorded != null) recorded.requireSame(refundId, orderNo, confirmedAt);
        else SettlementValues.require(!confirmationMonth.equals(currentMonth) || confirmationMonthClosed == currentMonthClosed,
                "같은 월의 마감 상태가 일치하지 않습니다.");
    }
}
