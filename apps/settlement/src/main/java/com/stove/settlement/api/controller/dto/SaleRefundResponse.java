package com.stove.settlement.api.controller.dto;

import com.stove.settlement.core.domain.SettlementRefund;
import java.time.Instant;

/** 매출에 연결된 환불 원장의 식별자와 확인 월·반영 월. 금액 조정은 환불 조회가 정본이다. */
public record SaleRefundResponse(String refundId, Instant confirmedAt, String refundMonth, String settlementMonth, String monthReason) {
    public static SaleRefundResponse from(SettlementRefund refund) {
        if (refund == null) return null;
        return new SaleRefundResponse(refund.getRefundId(), refund.getConfirmedAt(), refund.getRefundMonth(),
                refund.getSettlementMonth(), refund.getMonthReason());
    }
}
