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

@Entity
@Getter
@Table(name = "submission_snapshot")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubmissionSnapshot extends BaseTimeEntity {
    @Id private Long submissionId;
    @Column(nullable = false) private Long gameId;
    @Column(nullable = false, length = 50) private String productCode;
    @Column(nullable = false) private Long sellerId;
    @Column(nullable = false) private Long metadataRevision;
    @Column(nullable = false) private Long pricingRevision;
    @Column(nullable = false) private Long ratingRevision;
    @Column(nullable = false) private Long buildId;
    @Column(nullable = false, length = 200) private String title;
    @Column(nullable = false, length = 500) private String shortDescription;
    @Column(nullable = false) private long price;
    @Column(nullable = false, length = 3) private String currency;
    @Column(nullable = false, length = 30) private String ratingPath;
    @Column(nullable = false, length = 30) private String ratingPolicyVersion;
    @Column(nullable = false, length = 2) private String ratingCountry;
    @Column(name = "target_rating_code", nullable = false, length = 10)
    private String recommendedRatingCode;
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String ratingQuestionnaire;
    @Column(nullable = false, length = 30) private String productVersion;

    public static SubmissionSnapshot from(SubmissionCreatedEvent event) {
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
        return snapshot;
    }
}
