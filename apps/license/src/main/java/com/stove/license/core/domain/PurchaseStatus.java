package com.stove.license.core.domain;

/** 구매 근거(보관함 항목)의 상태. 결정 93·94·96. */
public enum PurchaseStatus {
    /** 보관함에 있고 아직 등록하지 않았다. */
    STORED,
    /** 라이브러리에 등록해 보유권이 있다. */
    REGISTERED,
    /** 환불로 정지됐다. 이전 상태는 suspendedFrom에 남는다. */
    SUSPENDED,
    /** 환불이 끝났다. 더 바뀌지 않는다. */
    REFUNDED
}
