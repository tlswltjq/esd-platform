package com.stove.settlement.api.controller.dto;

import com.stove.settlement.core.domain.SellerMonthView;

public record AdminMonthResponse(MonthResponse month, String sellerSubject, Long pgFeeAmount, Long platformNetAmount,
        String pgCostStatus, long confirmedPgFeeAmount, long pendingPgCostCount, Long salePgFeeAmount) {
    public static AdminMonthResponse from(SellerMonthView view) {
        return new AdminMonthResponse(MonthResponse.from(view.month()), view.month().getSellerSubject(),
                view.pgFeeAmount(), view.platformNetAmount(), view.pgCostStatus(),
                view.fees().confirmedFeeAmount(), view.pendingPgCostCount(),view.fees().feeAmount());
    }
}
