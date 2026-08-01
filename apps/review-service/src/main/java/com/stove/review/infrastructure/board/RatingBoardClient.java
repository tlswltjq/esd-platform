package com.stove.review.infrastructure.board;

/** 게임물관리위원회 접수 연동 포트. */
public interface RatingBoardClient {

    /** 심의 접수 → 접수번호 반환 */
    String submit(String productCode, String title, Long sellerId);
}
