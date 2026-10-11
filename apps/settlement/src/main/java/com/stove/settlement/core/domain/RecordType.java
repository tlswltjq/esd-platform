package com.stove.settlement.core.domain;

/**
 * 정산 원장에서 매출과 환불을 구분하는 유형.
 */
public enum RecordType {
    /** 판매 (양수 금액) */
    SALE,
    /** 환불 역산 (음수 금액) */
    REFUND
}
