package com.stove.settlement.core.domain;

/**
 * 오픈마켓 구조상 정산 규칙이 갈리는 축.
 * 자체 게임은 수수료가 없고, 입점 판매는 중개 수수료를 차감한 금액이 판매자 몫이 된다.
 */
public enum SaleType {
    /** 플랫폼 자체 판매로 수수료를 부과하지 않는 유형. */
    SELF,
    /** 입점 판매자의 수수료율을 적용하는 유형. */
    PARTNER
}
