package com.stove.studio.core.domain.release;

/**
 * 릴리스가 기존 공개 내용에 미치는 변경의 종류.
 */
public enum ReleaseChangeType {
    /** 게임을 처음 공개하는 릴리스. */
    INITIAL,
    /** 기존 공개 버전의 일반 패치. */
    NORMAL_PATCH,
    /** 재심사가 필요한 중대한 콘텐츠 변경. */
    MATERIAL_CHANGE,
    /** 이전에 공개한 자료와 빌드로 되돌리는 변경. */
    ROLLBACK
}
