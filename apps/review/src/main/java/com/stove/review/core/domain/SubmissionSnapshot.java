package com.stove.review.core.domain;

import com.stove.common.event.payload.SubmissionCreatedEvent;
import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 심사 접수 당시 제출된 자료와 빌드를 고정한 사본.
 */
@Entity
@Getter
@Table(name = "submission_snapshot")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubmissionSnapshot extends BaseTimeEntity {
    /** 심사할 자료와 빌드를 묶은 제출물 식별자. */
    @Id private Long submissionId;
    /** Studio 게임 프로젝트 식별자. */
    @Column(nullable = false) private Long gameId;
    /** 서비스 간 상품을 연결하는 고유 코드. */
    @Column(nullable = false, length = 50) private String productCode;
    /** 판매자를 나타내는 워크스페이스 식별자. */
    @Column(nullable = false) private Long sellerId;
    /** 상점 소개 자료 판본의 식별자. */
    @Column(nullable = false) private Long metadataRevision;
    /** 제출 또는 공개에 사용한 가격 판본 식별자. */
    @Column(nullable = false) private Long pricingRevision;
    /** 제출 또는 공개에 사용한 등급 자료 판본 식별자. */
    @Column(nullable = false) private Long ratingRevision;
    /** 배포 파일을 나타내는 빌드 식별자. */
    @Column(nullable = false) private Long buildId;
    /** 게임 또는 상점 페이지에 표시할 제목. */
    @Column(nullable = false, length = 200) private String title;
    /** 상점에 노출할 짧은 소개. */
    @Column(nullable = false, length = 500) private String shortDescription;
    /** 상품 한 개의 가격으로, 최소 화폐 단위의 정수. */
    @Column(nullable = false) private long price;
    /** 금액의 통화 코드. */
    @Column(nullable = false, length = 3) private String currency;
    /** 자체등급분류 또는 외부 등급분류 경로. */
    @Column(nullable = false, length = 30) private String ratingPath;
    /** 등급 판정에 적용한 정책 버전. */
    @Column(nullable = false, length = 30) private String ratingPolicyVersion;
    /** 등급분류 결과가 적용되는 국가 코드. */
    @Column(nullable = false, length = 2) private String ratingCountry;
    /** 설문과 정책으로 산출한 권장 이용 등급. */
    @Column(name = "target_rating_code", nullable = false, length = 10)
    private String recommendedRatingCode;
    /** 등급분류 판단에 사용한 설문 내용. */
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String ratingQuestionnaire;
    /** 빌드의 제품 버전 문자열. */
    @Column(nullable = false, length = 30) private String productVersion;
    /** 제출 시점의 플랫폼별 빌드 목록을 보관하는 JSON. */
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String buildVariantsJson;

    public static SubmissionSnapshot from(SubmissionCreatedEvent event) {
        return from(event, "[]");
    }

    public static SubmissionSnapshot from(SubmissionCreatedEvent event, String buildVariantsJson) {
        SubmissionSnapshot snapshot = new SubmissionSnapshot();
        snapshot.submissionId = event.submissionId();
        snapshot.gameId = event.gameId();
        snapshot.productCode = event.productCode();
        snapshot.sellerId = event.sellerId();
        snapshot.metadataRevision = event.metadataRevision();
        snapshot.pricingRevision = event.pricingRevision();
        snapshot.ratingRevision = event.ratingRevision();
        snapshot.buildId = event.buildId();
        snapshot.title = event.title();
        snapshot.shortDescription = event.shortDescription();
        snapshot.price = event.price();
        snapshot.currency = event.currency();
        snapshot.ratingPath = event.ratingPath();
        snapshot.ratingPolicyVersion = event.ratingPolicyVersion();
        snapshot.ratingCountry = event.ratingCountry();
        snapshot.recommendedRatingCode = event.recommendedRatingCode();
        snapshot.ratingQuestionnaire = event.ratingQuestionnaire();
        snapshot.productVersion = event.productVersion();
        snapshot.buildVariantsJson = buildVariantsJson;
        return snapshot;
    }
}
