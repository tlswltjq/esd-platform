package com.stove.settlement.infrastructure.tax;

/** 세금계산서 발행 연동 포트(국세청/전자세금계산서 사업자). */
public interface TaxInvoiceIssuer {

    /** @return 발행 번호 */
    String issue(Long sellerId, String settlementMonth, long netAmount);
}
