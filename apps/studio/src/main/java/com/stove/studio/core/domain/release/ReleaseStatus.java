package com.stove.studio.core.domain.release;

/**
 * 공개 예약부터 완료·취소·점검 실패까지의 릴리스 상태.
 */
public enum ReleaseStatus {
    /** 지정 시각에 공개하도록 예약된 상태. */
    SCHEDULED,
    /** 자료 또는 릴리스가 공개된 상태. */
    PUBLISHED,
    /** 예약 또는 요청이 취소된 상태. */
    CANCELLED,
    /** 출시 전 기본 실행 점검에 실패한 상태. */
    SMOKE_TEST_FAILED
}
