package com.stove.studio.core.domain.revision;

/**
 * 등급분류를 자체 처리할지 외부 기관에 신청할지의 구분.
 */
public enum RatingPath {
    /** 자체등급분류 경로. */
    SELF_CLASSIFICATION,
    /** 게임물관리위원회를 통한 외부 등급분류 경로. */
    GRAC
}
