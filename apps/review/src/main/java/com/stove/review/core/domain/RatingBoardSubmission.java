package com.stove.review.core.domain;

/**
 *  게임물관리위원회에 전달하는 불변 제출 스냅샷.
 *
 * @param submissionId 심사할 자료와 빌드를 묶은 제출물 식별자.
 * @param productCode 서비스 간 상품을 연결하는 고유 코드.
 * @param title 게임 또는 상점 페이지에 표시할 제목.
 * @param sellerId 판매자를 나타내는 워크스페이스 식별자.
 * @param buildId 배포 파일을 나타내는 빌드 식별자.
 * @param productVersion 빌드의 제품 버전 문자열.
 * @param policyVersion 판정에 적용한 등급분류 정책 버전.
 * @param country 자료 또는 정책이 적용되는 국가 코드.
 * @param targetRatingCode 외부 기관에 신청할 목표 이용 등급.
 * @param questionnaire 등급 판정에 사용한 설문 내용.
 */
public record RatingBoardSubmission(Long submissionId, String productCode, String title, Long sellerId,
                                    Long buildId, String productVersion, String policyVersion,
                                    String country, String targetRatingCode, String questionnaire) {
}
