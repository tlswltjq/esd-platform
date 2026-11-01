package com.stove.settlement.core.domain;

/** Uses the first confirmation month, falling back only to the current OPEN month. */
public final class UseConfirmedRefundMonthPolicy implements RefundSettlementMonthPolicy {
    @Override public RefundMonthSelection select(RefundMonthInput input) {
        SettlementValues.require(input != null, "검증한 환불 월 선택 입력이 필요합니다.");
        if (input.recorded() != null) return input.recorded();
        String confirmedMonth = SettlementMonth.at(input.confirmedAt()).value();
        String selected = confirmedMonth;
        String reason = "CONFIRMED_MONTH";
        if (input.confirmationMonthClosed()) {
            SettlementValues.consistent(!input.currentMonthClosed(), "환불을 반영할 현재 OPEN 월이 없습니다.");
            selected = SettlementMonth.at(input.receivedAt()).value(); reason = "CLOSED_MONTH_ROLLOVER";
        }
        return new RefundMonthSelection("REFUND_CONFIRMED_MONTH_V1", input.refundId(), input.orderNo(),
                input.confirmedAt(), input.receivedAt(), confirmedMonth, selected, reason);
    }
}
