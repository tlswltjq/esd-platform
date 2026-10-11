package com.stove.studio.core.domain.revision;

import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 등급 설문과 정책 판정 결과를 고정한 자료 판본.
 */
@Entity
@Getter
@Table(name = "rating_revision", uniqueConstraints =
        @UniqueConstraint(name = "uk_rating_revision", columnNames = {"gameId", "revisionNo"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RatingRevision extends BaseTimeEntity {
    /** 저장된 대상을 구별하는 식별자. */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    /** Studio 게임 프로젝트 식별자. */
    @Column(nullable = false) private Long gameId;
    /** 같은 프로젝트 내 자료 판본의 순번. */
    @Column(nullable = false) private int revisionNo;
    /** 판정에 적용한 등급분류 정책 버전. */
    @Column(nullable = false, length = 30) private String policyVersion;
    /** 자료 또는 정책이 적용되는 국가 코드. */
    @Column(nullable = false, length = 2) private String country;
    /** 설문과 정책으로 산출한 권장 이용 등급. */
    @Column(name = "target_rating_code", nullable = false, length = 10)
    private String recommendedRatingCode;
    /** 등급 판정에 사용한 설문 내용. */
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String questionnaire;
    /** 등급 설문으로 결정한 분류 경로. */
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private RatingPath resolvedPath;

    private RatingRevision(Long gameId, int revisionNo, String policyVersion,
                           String questionnaire, RatingPath resolvedPath, String country,
                           String recommendedRatingCode) {
        this.gameId = gameId;
        this.revisionNo = revisionNo;
        this.policyVersion = policyVersion;
        this.questionnaire = questionnaire;
        this.resolvedPath = resolvedPath;
        this.country = country;
        this.recommendedRatingCode = recommendedRatingCode;
    }

    public static RatingRevision create(Long gameId, int revisionNo, String policyVersion,
                                        String questionnaire, RatingPath resolvedPath, String country,
                                        String recommendedRatingCode) {
        return new RatingRevision(gameId, revisionNo, policyVersion, questionnaire, resolvedPath,
                country, recommendedRatingCode);
    }
}
