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
import java.util.Set;
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
    @Column(name = "target_rating_code", length = 10) private String recommendedRatingCode;
    @Column(length = 10) private String ratingCode;
    @Column(length = 100) private String certificationNumber;
    @Column(length = 100) private String issuer;
    private Instant issuedAt;
    @Column(length = 2) private String country;
    @Column(name = "external_submission_id", length = 100) private String externalApplicationNumber;
    private Instant externalSubmittedAt;
    @Column(length = 500) private String externalEvidenceUrl;
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

    public static ReviewCase ratingRequested(Long submissionId, String ratingPath,
                                             String recommendedRatingCode) {
        ReviewCase reviewCase = new ReviewCase(submissionId, ReviewType.RATING);
        reviewCase.ratingPath = ratingPath;
        reviewCase.recommendedRatingCode = recommendedRatingCode;
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

    public void approveSelfClassification(String actor, String approvedRatingCode, String approvedCountry) {
        requireOpen();
        if (!"SELF_CLASSIFICATION".equals(ratingPath)
                || !Set.of("ALL", "12", "15").contains(approvedRatingCode)
                || !approvedRatingCode.equals(recommendedRatingCode)
                || approvedCountry == null || !approvedCountry.matches("[A-Z]{2}")) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "자체등급은 정책이 결정한 전체·12·15세 등급과 대상 국가가 필요합니다.");
        }
        RatingDecision decision = new RatingDecision(approvedRatingCode, "ESD-SELF-" + submissionId,
                "ESD SELF CLASSIFICATION", Instant.now(), approvedCountry);
        approveWith(actor, decision);
    }

    public void submitExternal(String applicationNumber, Instant submittedAt, String evidenceUrl) {
        if (reviewType != ReviewType.RATING || !"GRAC".equals(ratingPath)
                || status != ReviewCaseStatus.REQUESTED) {
            throw new BusinessException(ErrorCode.CONFLICT, "외부 등급 접수를 시작할 수 없는 심사입니다.");
        }
        if (applicationNumber == null || applicationNumber.isBlank()
                || submittedAt == null || submittedAt.isAfter(Instant.now().plusSeconds(300))
                || evidenceUrl == null || !evidenceUrl.matches("^https://\\S+$")) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "외부 접수번호, 허용 시계 오차 이내의 접수 시각, HTTPS 증빙 URL이 필요합니다.");
        }
        externalApplicationNumber = applicationNumber;
        externalSubmittedAt = submittedAt;
        externalEvidenceUrl = evidenceUrl;
        status = ReviewCaseStatus.EXTERNAL_SUBMITTED;
    }

    public void approveExternalRating(String actor, RatingDecision rating) {
        requireOpen();
        if (!"GRAC".equals(ratingPath)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "GRAC 경로의 등급 심사만 외부 인증 결과로 승인할 수 있습니다.");
        }
        if (!hasExternalSubmission()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "GRAC 외부 접수번호와 제출 증빙이 있어야 승인할 수 있습니다.");
        }
        if (!completeRating(rating) || !"18".equals(rating.ratingCode())
                || !rating.ratingCode().equals(recommendedRatingCode)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "GRAC 외부 접수 증빙과 정책이 결정한 18세 인증 결과가 필요합니다.");
        }
        approveWith(actor, rating);
    }

    public boolean hasExternalSubmission() {
        return externalApplicationNumber != null && !externalApplicationNumber.isBlank()
                && externalSubmittedAt != null
                && externalEvidenceUrl != null && !externalEvidenceUrl.isBlank();
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
                && Set.of("ALL", "12", "15", "18").contains(rating.ratingCode())
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
