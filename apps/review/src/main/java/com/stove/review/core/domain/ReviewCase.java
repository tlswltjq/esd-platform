package com.stove.review.core.domain;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "review_case", uniqueConstraints =
        @UniqueConstraint(name = "uk_review_case_submission_type", columnNames = {"submissionId", "reviewType"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewCase extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long submissionId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private ReviewType reviewType;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private ReviewCaseStatus status;
    @Column(length = 100) private String decidedBy;
    @Column(length = 30) private String reasonCode;
    @Column(length = 1000) private String externalFeedback;
    @Column(length = 10) private String ratingCode;
    @Column(length = 100) private String certificationNumber;
    @Column(length = 100) private String issuer;
    private Instant issuedAt;
    @Column(length = 2) private String country;
    private Instant decidedAt;
    @Version @Column(nullable = false) private long entityVersion;

    private ReviewCase(Long submissionId, ReviewType reviewType) {
        this.submissionId = submissionId;
        this.reviewType = reviewType;
        this.status = ReviewCaseStatus.REQUESTED;
    }

    public static ReviewCase requested(Long submissionId, ReviewType reviewType) {
        return new ReviewCase(submissionId, reviewType);
    }

    public void approve(String actor, RatingDecision rating) {
        requireOpen();
        if (reviewType == ReviewType.RATING && !completeRating(rating)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "등급 코드, 인증번호, 발급기관, 발급일, 대상 국가가 모두 필요합니다.");
        }
        status = ReviewCaseStatus.APPROVED;
        decidedBy = actor;
        decidedAt = Instant.now();
        if (rating != null) {
            ratingCode = rating.ratingCode();
            certificationNumber = rating.certificationNumber();
            issuer = rating.issuer();
            issuedAt = rating.issuedAt();
            country = rating.country();
        }
    }

    private boolean completeRating(RatingDecision rating) {
        return rating != null
                && java.util.Set.of("ALL", "12", "15", "18").contains(rating.ratingCode())
                && rating.certificationNumber() != null && !rating.certificationNumber().isBlank()
                && rating.issuer() != null && !rating.issuer().isBlank()
                && rating.issuedAt() != null
                && rating.country() != null && rating.country().matches("[A-Z]{2}");
    }

    public void requestChanges(String actor, String reasonCode, String feedback) {
        requireOpen();
        status = ReviewCaseStatus.CHANGES_REQUESTED;
        decidedBy = actor;
        this.reasonCode = reasonCode;
        this.externalFeedback = feedback;
        decidedAt = Instant.now();
    }

    private void requireOpen() {
        if (status != ReviewCaseStatus.REQUESTED) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 결정된 심사입니다.");
        }
    }

    public record RatingDecision(String ratingCode, String certificationNumber,
                                 String issuer, Instant issuedAt, String country) {
    }
}
