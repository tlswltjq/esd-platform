package com.stove.studio.core.domain.submission;

/**
 * 심사 제출부터 출시 준비·공개까지의 제출물 상태.
 */
public enum SubmissionStatus {
    /** 자료를 제출해 심사 결과를 기다리는 상태. */
    SUBMITTED,
    /** 창작자에게 자료 수정을 요청한 상태. */
    CHANGES_REQUESTED,
    /** 필요한 심사를 통과해 출시할 수 있는 상태. */
    READY_FOR_RELEASE,
    /** 제출한 자료와 빌드가 공개된 상태. */
    RELEASED,
    /** 예약 또는 요청이 취소된 상태. */
    CANCELLED
}
