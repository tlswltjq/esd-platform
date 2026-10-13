package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;
import java.util.UUID;

/**
 * 제출물의 유형별 심사 승인 사실을 다른 서비스에 전달하는 이벤트.
 *
 * @param eventId 서비스 간 전달 시 유지하는 이벤트 고유 식별자.
 * @param occurredAt 업무 행동을 기록한 시각.
 * @param reviewId 프로젝트 단위 심사 요청 식별자.
 * @param submissionId 심사할 자료와 빌드를 묶은 제출물 식별자.
 * @param buildId 배포 파일을 나타내는 빌드 식별자.
 * @param metadataRevision 상점 소개 자료 판본의 식별자.
 * @param pricingRevision 제출 또는 공개에 사용한 가격 판본 식별자.
 * @param ratingRevision 제출 또는 공개에 사용한 등급 자료 판본 식별자.
 * @param reviewType 등급·상점·빌드 등 심사할 업무 유형.
 * @param productCode 서비스 간 상품을 연결하는 고유 코드.
 * @param ratingCode 확정된 이용 등급 코드.
 * @param certificationNumber 등급분류 결정의 증명 번호.
 * @param issuer 등급분류 결과를 발급한 기관.
 * @param issuedAt 증명 또는 이용권을 발급한 시각.
 * @param country 자료 또는 정책이 적용되는 국가 코드.
 * @param ratingPath 자체등급분류 또는 외부 등급분류 경로.
 * @param ratingPolicyVersion 등급 판정에 적용한 정책 버전.
 * @param externalApplicationNumber 외부 등급분류 기관에 접수한 신청 번호.
 * @param externalEvidenceUrl 외부 등급분류 결과의 증빙 URL.
 */
public record SubmissionReviewApprovedEvent(
        String eventId, Instant occurredAt,
        Long reviewId, Long submissionId, Long buildId,
        Long metadataRevision, Long pricingRevision, Long ratingRevision,
        String reviewType, String productCode,
        String ratingCode, String certificationNumber, String issuer,
        Instant issuedAt, String country, String ratingPath, String ratingPolicyVersion,
        String externalApplicationNumber, String externalEvidenceUrl
) implements DomainEvent {
    public static SubmissionReviewApprovedEvent of(
            Long reviewId, Long submissionId, Long buildId,
            Long metadataRevision, Long pricingRevision, Long ratingRevision,
            String reviewType, String productCode,
            String ratingCode, String certificationNumber, String issuer,
            Instant issuedAt, String country, String ratingPath, String ratingPolicyVersion,
            String externalApplicationNumber, String externalEvidenceUrl) {
        return new SubmissionReviewApprovedEvent(UUID.randomUUID().toString(), Instant.now(),
                reviewId, submissionId, buildId, metadataRevision, pricingRevision, ratingRevision,
                reviewType, productCode, ratingCode, certificationNumber, issuer, issuedAt, country,
                ratingPath, ratingPolicyVersion, externalApplicationNumber, externalEvidenceUrl);
    }
    @Override public String eventType() { return EventType.SUBMISSION_REVIEW_APPROVED; }
    @Override public String topic() { return Topics.REVIEW; }
    @Override public String partitionKey() { return productCode; }
}
