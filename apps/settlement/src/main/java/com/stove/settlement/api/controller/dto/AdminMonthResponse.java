package com.stove.settlement.api.controller.dto;

import com.stove.settlement.core.domain.SellerMonthView;

public record AdminMonthResponse(MonthResponse month, String sellerSubject, Long pgFeeAmount, Long platformNetAmount,
        String pgCostStatus, long confirmedPgFeeAmount, long pendingPgCostCount, Long salePgFeeAmount, Long refundPgFeeReturnAmount, long confirmedRefundPgFeeReturnAmount,
        Long salePgFeeVatAmount, Long refundPgFeeVatReturnAmount, long confirmedNetPgCostAmount) {
    public static AdminMonthResponse from(SellerMonthView view) {
        return new AdminMonthResponse(MonthResponse.from(view.month()), view.month().getSellerSubject(),
                view.pgFeeAmount(), view.platformNetAmount(), view.pgCostStatus(),
                view.fees().confirmedFeeAmount(), view.pendingPgCostCount(),view.fees().feeAmount(),view.refundReturns().amount(),view.refundReturns().confirmedAmount(),
                view.saleCosts().amount()==null?null:view.saleCosts().vat(),view.refundReturns().amount()==null?null:view.refundReturns().vat(),view.confirmedNetCost());
    }
}
