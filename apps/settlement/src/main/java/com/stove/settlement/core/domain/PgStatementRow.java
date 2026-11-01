package com.stove.settlement.core.domain;

import java.time.LocalDate;

/** Signed amounts reported by Toss Pay; fee VAT is separate from sales VAT. */
public record PgStatementRow(String transactionType, String transactionId, String payToken, String orderNo,
        String productDesc, long amount, int fee, int vat, int feeVatSum, String payMethod, LocalDate dueDate) {
    public PgStatementRow {
        boolean pay="PAY".equals(transactionType);
        SettlementValues.require((pay || "REFUND".equals(transactionType)) && SettlementValues.text(transactionId,100)
                && SettlementValues.text(payToken,100) && SettlementValues.text(orderNo,100) && dueDate!=null
                && (productDesc==null || productDesc.length()<=2000),"PG 명세 거래의 식별자와 날짜가 올바르지 않습니다.");
        SettlementValues.require((pay ? amount>0 && fee<=0 && vat<=0 : amount<0 && amount!=Long.MIN_VALUE && fee>=0 && vat>=0)
                && (long)fee+vat==feeVatSum,"PG 명세의 거래/수수료/부가세 부호 또는 합계가 다릅니다.");
        SettlementValues.require("CARD".equals(payMethod) || "TOSS_MONEY".equals(payMethod) || "RESELLER_CARD".equals(payMethod),
                "지원하지 않는 PG 정산 결제수단입니다.");
        Math.addExact(amount,feeVatSum);
    }
    public long netAmount() { return Math.addExact(amount,feeVatSum); }
}
