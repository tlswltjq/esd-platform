package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 릴리스 공개 완료 사실을 다른 서비스에 전달하는 이벤트.
 *
 * @param eventId 서비스 간 전달 시 유지하는 이벤트 고유 식별자.
 * @param occurredAt 업무 행동을 기록한 시각.
 * @param releaseId 대상 릴리스 식별자.
 * @param previousReleaseId 변경 또는 롤백의 기준이 되는 이전 릴리스 식별자.
 * @param submissionId 심사할 자료와 빌드를 묶은 제출물 식별자.
 * @param gameId Studio 게임 프로젝트 식별자.
 * @param productCode 서비스 간 상품을 연결하는 고유 코드.
 * @param sellerId 판매자를 나타내는 워크스페이스 식별자.
 * @param buildId 배포 파일을 나타내는 빌드 식별자.
 * @param metadataRevision 상점 소개 자료 판본의 식별자.
 * @param pricingRevision 제출 또는 공개에 사용한 가격 판본 식별자.
 * @param ratingRevision 제출 또는 공개에 사용한 등급 자료 판본 식별자.
 * @param title 게임 또는 상점 페이지에 표시할 제목.
 * @param shortDescription 상점에 노출할 짧은 소개.
 * @param price 상품 한 개의 가격으로, 최소 화폐 단위의 정수.
 * @param currency 금액의 통화 코드.
 * @param ratingCode 확정된 이용 등급 코드.
 * @param productVersion 빌드의 제품 버전 문자열.
 * @param fileSize 배포 파일의 크기(바이트).
 * @param checksum 파일 무결성 검증에 사용하는 체크섬.
 * @param storagePath 파일이 보관된 객체 저장소 경로.
 * @param channel 릴리스를 공개하거나 설치를 허용할 배포 채널.
 * @param productKind 기본 게임·체험판·DLC·에디션·번들 구분.
 * @param parentProductCode 파생 상품이 연결되는 원본 상품 코드.
 * @param editionName 에디션 구분을 위해 표시하는 이름.
 * @param bundleProductCodes 번들에 포함된 상품 코드 목록.
 * @param buildVariants 플랫폼·아키텍처별 배포 파일 목록.
 * @param storefront 공개된 상점 소개 자료의 스냅샷.
 */
public record ReleasePublishedEvent(
        String eventId, Instant occurredAt,
        Long releaseId, Long previousReleaseId, Long submissionId,
        Long gameId, String productCode, Long sellerId,
        Long buildId, Long metadataRevision, Long pricingRevision, Long ratingRevision,
        String title, String shortDescription, long price, String currency,
        String ratingCode, String productVersion,
        long fileSize, String checksum, String storagePath, String channel,
        String productKind, String parentProductCode, String editionName,
        List<String> bundleProductCodes,
        List<BuildVariant> buildVariants,
        StorefrontSnapshot storefront
) implements DomainEvent {
    public static ReleasePublishedEvent of(
            Long releaseId, Long previousReleaseId, Long submissionId,
            Long gameId, String productCode, Long sellerId,
            Long buildId, Long metadataRevision, Long pricingRevision, Long ratingRevision,
            String title, String shortDescription, long price, String currency,
            String ratingCode, String productVersion, long fileSize, String checksum, String storagePath) {
        return of(releaseId, previousReleaseId, submissionId, gameId, productCode, sellerId,
                buildId, metadataRevision, pricingRevision, ratingRevision, title, shortDescription,
                price, currency, ratingCode, productVersion, fileSize, checksum, storagePath,
                "BASIC", null, null, List.of(), List.of());
    }

    public static ReleasePublishedEvent of(
            Long releaseId, Long previousReleaseId, Long submissionId,
            Long gameId, String productCode, Long sellerId,
            Long buildId, Long metadataRevision, Long pricingRevision, Long ratingRevision,
            String title, String shortDescription, long price, String currency,
            String ratingCode, String productVersion, long fileSize, String checksum, String storagePath,
            String productKind, String parentProductCode, String editionName,
            List<String> bundleProductCodes) {
        return of(releaseId, previousReleaseId, submissionId, gameId, productCode, sellerId,
                buildId, metadataRevision, pricingRevision, ratingRevision, title, shortDescription,
                price, currency, ratingCode, productVersion, fileSize, checksum, storagePath,
                productKind, parentProductCode, editionName, bundleProductCodes, List.of());
    }

    public static ReleasePublishedEvent of(
            Long releaseId, Long previousReleaseId, Long submissionId,
            Long gameId, String productCode, Long sellerId,
            Long buildId, Long metadataRevision, Long pricingRevision, Long ratingRevision,
            String title, String shortDescription, long price, String currency,
            String ratingCode, String productVersion, long fileSize, String checksum, String storagePath,
            String productKind, String parentProductCode, String editionName,
            List<String> bundleProductCodes, List<BuildVariant> buildVariants) {
        return of(releaseId, previousReleaseId, submissionId, gameId, productCode, sellerId,
                buildId, metadataRevision, pricingRevision, ratingRevision, title, shortDescription,
                price, currency, ratingCode, productVersion, fileSize, checksum, storagePath,
                productKind, parentProductCode, editionName, bundleProductCodes, buildVariants, null);
    }

    public static ReleasePublishedEvent of(
            Long releaseId, Long previousReleaseId, Long submissionId,
            Long gameId, String productCode, Long sellerId,
            Long buildId, Long metadataRevision, Long pricingRevision, Long ratingRevision,
            String title, String shortDescription, long price, String currency,
            String ratingCode, String productVersion, long fileSize, String checksum, String storagePath,
            String productKind, String parentProductCode, String editionName,
            List<String> bundleProductCodes, List<BuildVariant> buildVariants,
            StorefrontSnapshot storefront) {
        return new ReleasePublishedEvent(UUID.randomUUID().toString(), Instant.now(), releaseId,
                previousReleaseId, submissionId, gameId, productCode, sellerId, buildId,
                metadataRevision, pricingRevision, ratingRevision, title, shortDescription, price,
                currency, ratingCode, productVersion, fileSize, checksum, storagePath, "LIVE",
                productKind, parentProductCode, editionName, List.copyOf(bundleProductCodes),
                List.copyOf(buildVariants), storefront);
    }
    @Override public String eventType() { return EventType.RELEASE_PUBLISHED; }
    @Override public String topic() { return Topics.STUDIO; }
    @Override public String partitionKey() { return productCode; }
}
