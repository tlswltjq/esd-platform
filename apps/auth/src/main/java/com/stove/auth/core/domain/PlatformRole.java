package com.stove.auth.core.domain;

/**
 * 플랫폼에서 계정에 부여할 업무 역할.
 */
public enum PlatformRole {
    /** 게임 구매와 이용을 위한 일반 회원 역할. */
    MEMBER,
    /** 게임 제작·등록·출시를 담당하는 창작자 역할. */
    CREATOR,
    /** 제출된 게임과 자료를 심사하는 역할. */
    REVIEWER,
    /** 플랫폼 운영과 권한 관리를 담당하는 역할. */
    ADMIN
}
