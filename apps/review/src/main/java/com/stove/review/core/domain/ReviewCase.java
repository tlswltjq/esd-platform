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
    @Column(length = 30) private String ratingPath;
    @Column(length = 10) private String targetRatingCode;
    @Column(length = 50) private String externalSubmissionId;
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

    public static ReviewCase selfClassification(Long submissionId, String targetRatingCode) {
        ReviewCase reviewCase = new ReviewCase(submissionId, ReviewType.RATING);
        reviewCase.ratingPath = "SELF_CLASSIFICATION";
        reviewCase.targetRatingCode = targetRatingCode;
        return reviewCase;
    }

    public static ReviewCase externalSubmitted(Long submissionId, String targetRatingCode,
                                               String externalSubmissionId) {
        if (externalSubmissionId == null || externalSubmissionId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "GRAC 외부 접수번호가 필요합니다.");
        }
        ReviewCase reviewCase = new ReviewCase(submissionId, ReviewType.RATING);
        reviewCase.ratingPath = "GRAC";
        reviewCase.targetRatingCode = targetRatingCode;
        reviewCase.externalSubmissionId = externalSubmissionId;
        reviewCase.status = ReviewCaseStatus.EXTERNAL_SUBMITTED;
        return reviewCase;
    }

    public void approve(String actor) {
        requireOpen();
        if (reviewType == ReviewType.RATING) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "등급 심의는 등급 경로에 맞는 승인 메서드를 사용해야 합니다.");
        }
        approveWith(actor, null);
    }

    public void approveSelfClassification(String actor, String ratingCode, String country) {
        requireOpen();
        if (!"SELF_CLASSIFICATION".equals(ratingPath)
                || !java.util.Set.of("ALL", "12", "15").contains(ratingCode)
                || !ratingCode.equals(targetRatingCode)
                || country == null || !country.matches("[A-Z]{2}")) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "자체등급은 제출한 목표 등급(ALL/12/15)과 대상 국가가 필요합니다.");
        }
        RatingDecision decision = new RatingDecision(ratingCode, "ESD-SELF-" + submissionId,
                "ESD SELF CLASSIFICATION", Instant.now(), country);
        approveWith(actor, decision);
    }

    public void approveExternalRating(String actor, RatingDecision rating) {
        requireOpen();
        if (!"GRAC".equals(ratingPath) || externalSubmissionId == null || !completeRating(rating)
                || !"18".equals(rating.ratingCode()) || !rating.ratingCode().equals(targetRatingCode)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "GRAC 접수번호와 18세 등급의 완전한 외부 인증 증빙이 필요합니다.");
        }
        approveWith(actor, rating);
    }

    private void approveWith(String actor, RatingDecision rating) {
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
        if (status != ReviewCaseStatus.REQUESTED && status != ReviewCaseStatus.EXTERNAL_SUBMITTED) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 결정된 심사입니다.");
        }
    }

    public record RatingDecision(String ratingCode, String certificationNumber,
                                 String issuer, Instant issuedAt, String country) {
    }
}
