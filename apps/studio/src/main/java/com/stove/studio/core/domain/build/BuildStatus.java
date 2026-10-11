package com.stove.studio.core.domain.build;

/**
 * 업로드한 빌드의 파일 검증 및 보관 상태.
 */
public enum BuildStatus {
    /** 빌드 파일을 업로드하는 중. */
    UPLOADING,
    /** 업로드한 파일의 검증을 진행 중. */
    PROCESSING,
    /** 파일 검증을 통과해 제출에 사용할 수 있는 상태. */
    VALIDATED,
    /** 업로드한 파일의 검증에 실패한 상태. */
    FAILED,
    /** 보관 정책에 따라 빌드를 퇴역시킨 상태. */
    RETIRED
}
