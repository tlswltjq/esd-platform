package com.stove.studio.core.domain.access;

/**
 * 프로젝트 인증 정보의 유효 여부.
 */
public enum ProjectCredentialStatus {
    /** 폐기되지 않은 인증 정보로, 사용 시 만료 여부도 확인. */
    ACTIVE,
    /** 권한이 회수되어 사용할 수 없는 상태. */
    REVOKED
}
