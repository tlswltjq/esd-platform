package com.stove.settlement.core.domain;

public record PgPostedCostGroup(String sellerSubject,String businessMonth,String transactionType,
        long saleCount,long refundCount,long reportedFee,long reportedVat) {
    public long confirmedCount() {return "PAY".equals(transactionType)?saleCount:refundCount;}
    public long fee() {return "PAY".equals(transactionType)?Math.negateExact(reportedFee):reportedFee;}
    public long vat() {return "PAY".equals(transactionType)?Math.negateExact(reportedVat):reportedVat;}
}
