package com.stove.catalog.domain;

/**
 * 상품 노출 상태.
 * studio 등록 → review 심의 → catalog 노출 전환 파이프라인의 종착점.
 */
public enum ProductStatus {
    /** 스튜디오에서 작성 중 */
    DRAFT,
    /** 등급분류 심의 진행 중 */
    REVIEWING,
    /** 심의 승인, 판매 시작 전 */
    APPROVED,
    /** 판매 중 (구매 가능한 유일한 상태) */
    ON_SALE,
    /** 운영 사유로 판매 중지 */
    SUSPENDED,
    /** 판매 종료 */
    CLOSED;

    public boolean purchasable() {
        return this == ON_SALE;
    }
}
