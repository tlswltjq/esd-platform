package com.stove.studio.core.domain.revision;

/**
 * 상점 소개 판본의 작성·공개 상태.
 */
public enum StorePageRevisionStatus {
    /** 자료를 작성 중이며 아직 제출 또는 공개하지 않은 상태. */
    DRAFT,
    /** 자료 또는 릴리스가 공개된 상태. */
    PUBLISHED
}
