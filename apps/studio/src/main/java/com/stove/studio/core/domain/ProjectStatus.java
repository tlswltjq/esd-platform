package com.stove.studio.core.domain;

/** 게임 프로젝트 상태. 심의 결과 이벤트로만 SUBMITTED 이후 상태가 바뀐다. */
public enum ProjectStatus {
    /** 작성 중 */
    DRAFT,
    /** 심의 신청 완료 */
    SUBMITTED,
    /** 심의 승인 → catalog 에 상품이 생성됨 */
    APPROVED,
    /** 반려 — 사유 확인 후 재신청 가능 */
    REJECTED
}
