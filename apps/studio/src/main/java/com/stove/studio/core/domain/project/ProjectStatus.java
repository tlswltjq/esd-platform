package com.stove.studio.core.domain.project;

/** 게임 프로젝트 상태. 심의 결과 이벤트로만 SUBMITTED 이후 상태가 바뀐다. */
public enum ProjectStatus {
    /** 자료를 작성 중이며 아직 제출 또는 공개하지 않은 상태. */
    DRAFT,
    /** 자료를 제출해 심사 결과를 기다리는 상태. */
    SUBMITTED,
    /** 심의 승인 → catalog 에 상품이 생성됨 */
    APPROVED,
    /** 반려 — 사유 확인 후 재신청 가능 */
    REJECTED
}
