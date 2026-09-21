package com.stove.studio.core.domain;

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

@Entity
@Getter
@Table(name = "rating_revision", uniqueConstraints =
        @UniqueConstraint(name = "uk_rating_revision", columnNames = {"gameId", "revisionNo"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RatingRevision extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long gameId;
    @Column(nullable = false) private int revisionNo;
    @Column(nullable = false, length = 30) private String policyVersion;
    @Column(nullable = false, length = 2) private String country;
    @Column(nullable = false, length = 10) private String targetRatingCode;
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String questionnaire;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private RatingPath resolvedPath;

    private RatingRevision(Long gameId, int revisionNo, String policyVersion, String country,
                           String targetRatingCode,
                           String questionnaire, RatingPath resolvedPath) {
        this.gameId = gameId;
        this.revisionNo = revisionNo;
        this.policyVersion = policyVersion;
        this.country = country;
        this.targetRatingCode = targetRatingCode;
        this.questionnaire = questionnaire;
        this.resolvedPath = resolvedPath;
    }

    public static RatingRevision create(Long gameId, int revisionNo, String policyVersion, String country,
                                        String targetRatingCode,
                                        String questionnaire, RatingPath resolvedPath) {
        return new RatingRevision(gameId, revisionNo, policyVersion, country, targetRatingCode,
                questionnaire, resolvedPath);
    }
}
