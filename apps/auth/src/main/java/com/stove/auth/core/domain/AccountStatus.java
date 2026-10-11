package com.stove.auth.core.domain;

/**
 * 로그인 계정의 사용 가능 상태.
 */
public enum AccountStatus {
    /** 로그인하여 계정을 사용할 수 있는 상태. */
    ACTIVE,
    /** 로그인이 잠긴 계정. */
    LOCKED,
    /** 사용이 비활성화된 계정. */
    DISABLED
}
