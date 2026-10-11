package com.stove.studio.core.domain.project;

/**
 * 작업 공간의 사용 가능 상태.
 */
public enum WorkspaceStatus {
    /** 프로젝트 작업에 사용할 수 있는 작업 공간. */
    ACTIVE,
    /** 작업 공간 사용이 중지된 상태. */
    SUSPENDED
}
