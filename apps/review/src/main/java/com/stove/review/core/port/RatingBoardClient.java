package com.stove.review.core.port;

import com.stove.review.core.domain.RatingBoardSubmission;

/** 게임물관리위원회 접수 연동 포트. */
public interface RatingBoardClient {

    /** 기존 프로젝트 단위 심의 접수 → 접수번호 반환. */
    String submitLegacy(String productCode, String title, Long sellerId);

    /**
     * 불변 제출 스냅샷을 게임물관리위원회에 접수한다.
     * 구현체는 {@code submissionId}를 멱등키로 사용해야 한다.
     */
    String submit(RatingBoardSubmission submission);
}
