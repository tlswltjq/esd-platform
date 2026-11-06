package com.stove.license.core.domain;

/** 보유권(등록 1번)의 상태. 결정 95. */
public enum LicenseStatus {
    ACTIVE,
    SUSPENDED,
    /** 환불 완료 또는 보관함 반환으로 끝났다. 이력으로만 남는다. */
    ENDED
}
