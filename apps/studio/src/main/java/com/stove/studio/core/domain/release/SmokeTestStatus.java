package com.stove.studio.core.domain.release;

/**
 * 릴리스 공개 전 기본 실행 점검의 결과.
 */
public enum SmokeTestStatus {
    /** 출시 전 기본 실행 점검을 아직 수행하지 않은 상태. */
    PENDING,
    /** 출시 전 기본 실행 점검을 통과한 상태. */
    PASSED,
    /** 출시 전 기본 실행 점검에 실패한 상태. */
    FAILED
}
