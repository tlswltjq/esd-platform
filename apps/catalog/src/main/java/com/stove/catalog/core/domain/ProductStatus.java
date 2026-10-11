package com.stove.catalog.core.domain;

/**
 * 상품 노출 상태.
 * studio 등록 → review 심의 → catalog 노출 전환 파이프라인의 종착점.
 */
public enum ProductStatus {
    /** 자료를 작성 중이며 아직 제출 또는 공개하지 않은 상태. */
    DRAFT,
    /** 등급분류 심의 진행 중 */
    REVIEWING,
    /** 심의 승인, 판매 시작 전 */
    APPROVED,
    /** 판매 중 (구매 가능한 유일한 상태) */
    ON_SALE,
    /** 운영 사유로 판매 중지 */
    SUSPENDED,
    /** 상품 판매가 종료된 상태. */
    CLOSED;

    public boolean purchasable() {
        return this == ON_SALE;
    }
}
