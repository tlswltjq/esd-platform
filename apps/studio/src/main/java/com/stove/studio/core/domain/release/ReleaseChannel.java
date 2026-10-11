package com.stove.studio.core.domain.release;

/**
 * 개발·테스트·출시 검증·일반 공개를 구분하는 배포 채널.
 */
public enum ReleaseChannel {
    /** 개발 과정의 내부 배포 채널. */
    DEV,
    /** 내부 테스터에게 제공하는 배포 채널. */
    TEST,
    /** 일반 공개 전 검증용 배포 채널. */
    STAGE,
    /** 고객에게 일반 공개하는 배포 채널. */
    LIVE
}
