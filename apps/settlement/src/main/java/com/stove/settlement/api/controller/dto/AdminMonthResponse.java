package com.stove.settlement.api.controller.dto;

import com.stove.settlement.core.domain.SellerMonthView;

public record AdminMonthResponse(MonthResponse month, String sellerSubject, Long pgFeeAmount, Long platformNetAmount,
        String pgCostStatus, long confirmedPgFeeAmount, long pendingPgCostCount) {
    public static AdminMonthResponse from(SellerMonthView view) {
        return new AdminMonthResponse(MonthResponse.from(view.month()), view.month().getSellerSubject(),
                view.pgFeeAmount(), view.platformNetAmount(), view.fees().status(),
                view.fees().confirmedFeeAmount(), view.fees().pendingCount());
    }
}
