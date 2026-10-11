package com.stove.license.core.domain;

/**
 * 이용권의 유효 여부.
 */
public enum LicenseStatus {
    /** 보유 중(라이브러리 노출, 다운로드 가능) */
    ACTIVE,
    /** 권한이 회수되어 사용할 수 없는 상태. */
    REVOKED
}
