package com.stove.studio.core.domain.build;

/**
 * 분할 업로드의 진행·완료·만료·중단 상태.
 */
public enum UploadSessionStatus {
    /** 파일 조각을 업로드할 수 있는 열린 세션. */
    OPEN,
    /** 파일 조각 결합을 완료한 세션. */
    COMPLETED,
    /** 유효 기간 또는 처리 기한이 지난 상태. */
    EXPIRED,
    /** 업로드가 중단된 세션. */
    ABORTED
}
