package com.stove.review.core.domain;

/** 게임물관리위원회에 전달하는 불변 제출 스냅샷. */
public record RatingBoardSubmission(Long submissionId, String productCode, String title, Long sellerId,
                                    Long buildId, String productVersion, String policyVersion,
                                    String country, String targetRatingCode, String questionnaire) {
}
