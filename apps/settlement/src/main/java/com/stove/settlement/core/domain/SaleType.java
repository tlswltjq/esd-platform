package com.stove.settlement.core.domain;

/**
 * 오픈마켓 구조상 정산 규칙이 갈리는 축.
 * 자체 게임은 수수료가 없고, 입점 판매는 중개 수수료를 차감한 금액이 판매자 몫이 된다.
 */
public enum SaleType {
    /** 자체 판매(스마일게이트 자체 게임) */
    SELF,
    /** 입점 판매(3rd party) */
    PARTNER
}
