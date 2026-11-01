package com.stove.settlement.core.domain;

import java.time.Instant;

/** Immutable month evidence to be stored with a refund posting by its application service. */
public record RefundMonthSelection(String policyId, String refundId, String orderNo, Instant confirmedAt,
        Instant firstReceivedAt, String refundMonth, String settlementMonth, String reason) {
    public RefundMonthSelection {
        SettlementValues.require(SettlementValues.text(policyId, 100) && SettlementValues.uuid(refundId)
                && SettlementValues.uuid(orderNo) && SettlementValues.text(reason, 100), "환불 월 선택의 정책·원거래 근거가 필요합니다.");
        SettlementValues.require(SettlementValues.microsecond(confirmedAt) && SettlementValues.microsecond(firstReceivedAt)
                && !firstReceivedAt.isBefore(confirmedAt), "환불 확인·최초 접수 시각이 올바르지 않습니다.");
        new SettlementMonth(refundMonth); new SettlementMonth(settlementMonth);
        SettlementValues.require(refundMonth.equals(SettlementMonth.at(confirmedAt).value())
                && settlementMonth.compareTo(SettlementMonth.at(firstReceivedAt).value()) <= 0,
                "환불 확인 월 또는 반영 월이 올바르지 않습니다.");
    }

    public void requireSame(String expectedRefundId, String expectedOrderNo, Instant expectedConfirmedAt) {
        SettlementValues.consistent(refundId.equals(expectedRefundId) && orderNo.equals(expectedOrderNo)
                && confirmedAt.equals(expectedConfirmedAt), "최초 반영 월에 대응하는 환불 근거가 다릅니다.");
    }
}
