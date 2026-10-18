package com.stove.settlement.api.controller.dto;

import com.stove.settlement.core.domain.SellerMonthlySettlement;

public record AdminMonthResponse(MonthResponse month,String sellerSubject,long pgFeeAmount,long platformNetAmount) {
    public static AdminMonthResponse from(SellerMonthlySettlement month) {
        return new AdminMonthResponse(MonthResponse.from(month),month.getSellerSubject(),month.getPgFeeAmount(),month.getPlatformNetAmount());
    }
}
