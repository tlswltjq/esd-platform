package com.stove.studio.core.domain.project;

/** Creator-managed commercial product type. */
public enum ProductKind {
    /** 기본 게임 상품. */
    BASIC,
    /** 원본 게임의 체험판. */
    DEMO,
    /** 원본 게임에 연결되는 추가 콘텐츠. */
    DLC,
    /** 원본 게임의 별도 에디션 상품. */
    EDITION,
    /** 여러 게임을 묶어 구성한 상품. */
    BUNDLE
}
