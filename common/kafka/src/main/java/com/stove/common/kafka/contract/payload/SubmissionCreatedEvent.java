package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 심사 제출물 생성 사실을 다른 서비스에 전달하는 이벤트.
 *
 * @param eventId 서비스 간 전달 시 유지하는 이벤트 고유 식별자.
 * @param occurredAt 업무 행동을 기록한 시각.
 * @param submissionId 심사할 자료와 빌드를 묶은 제출물 식별자.
 * @param gameId Studio 게임 프로젝트 식별자.
 * @param productCode 서비스 간 상품을 연결하는 고유 코드.
 * @param sellerId 판매자를 나타내는 워크스페이스 식별자.
 * @param metadataRevision 상점 소개 자료 판본의 식별자.
 * @param pricingRevision 제출 또는 공개에 사용한 가격 판본 식별자.
 * @param ratingRevision 제출 또는 공개에 사용한 등급 자료 판본 식별자.
 * @param buildId 배포 파일을 나타내는 빌드 식별자.
 * @param title 게임 또는 상점 페이지에 표시할 제목.
 * @param shortDescription 상점에 노출할 짧은 소개.
 * @param price 상품 한 개의 가격으로, 최소 화폐 단위의 정수.
 * @param currency 금액의 통화 코드.
 * @param ratingPath 자체등급분류 또는 외부 등급분류 경로.
 * @param ratingPolicyVersion 등급 판정에 적용한 정책 버전.
 * @param ratingCountry 등급분류 결과가 적용되는 국가 코드.
 * @param recommendedRatingCode 설문과 정책으로 산출한 권장 이용 등급.
 * @param ratingQuestionnaire 등급분류 판단에 사용한 설문 내용.
 * @param productVersion 빌드의 제품 버전 문자열.
 * @param buildVariants 플랫폼·아키텍처별 배포 파일 목록.
 */
public record SubmissionCreatedEvent(
        String eventId, Instant occurredAt,
        Long submissionId, Long gameId, String productCode, Long sellerId,
        Long metadataRevision, Long pricingRevision, Long ratingRevision, Long buildId,
        String title, String shortDescription, long price, String currency,
        String ratingPath, String ratingPolicyVersion, String ratingCountry,
        String recommendedRatingCode, String ratingQuestionnaire, String productVersion,
        List<BuildVariant> buildVariants
) implements DomainEvent {
    public static SubmissionCreatedEvent of(Long submissionId, Long gameId, String productCode, Long sellerId,
                                            Long metadataRevision, Long pricingRevision, Long ratingRevision,
                                            Long buildId, String title, String shortDescription, long price,
                                            String currency, String ratingPath, String ratingPolicyVersion,
                                            String ratingCountry, String recommendedRatingCode,
                                            String ratingQuestionnaire, String productVersion) {
        return of(submissionId, gameId, productCode, sellerId, metadataRevision, pricingRevision,
                ratingRevision, buildId, title, shortDescription, price, currency, ratingPath,
                ratingPolicyVersion, ratingCountry, recommendedRatingCode, ratingQuestionnaire,
                productVersion, List.of());
    }

    public static SubmissionCreatedEvent of(Long submissionId, Long gameId, String productCode, Long sellerId,
                                            Long metadataRevision, Long pricingRevision, Long ratingRevision,
                                            Long buildId, String title, String shortDescription, long price,
                                            String currency, String ratingPath, String ratingPolicyVersion,
                                            String ratingCountry, String recommendedRatingCode,
                                            String ratingQuestionnaire, String productVersion,
                                            List<BuildVariant> buildVariants) {
        return new SubmissionCreatedEvent(UUID.randomUUID().toString(), Instant.now(), submissionId, gameId,
                productCode, sellerId, metadataRevision, pricingRevision, ratingRevision, buildId,
                title, shortDescription, price, currency, ratingPath, ratingPolicyVersion, ratingCountry,
                recommendedRatingCode, ratingQuestionnaire, productVersion, List.copyOf(buildVariants));
    }
    @Override public String eventType() { return EventType.SUBMISSION_CREATED; }
    @Override public String topic() { return Topics.STUDIO; }
    @Override public String partitionKey() { return productCode; }
}
