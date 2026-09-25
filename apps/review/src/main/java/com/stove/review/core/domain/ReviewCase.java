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
import java.time.temporal.ChronoUnit;
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
    @Column(length = 2000) private String internalMemo;
    @Column(length = 500) private String evidenceUrl;
    @Column(length = 100) private String assignedTo;
    private Instant assignedAt;
    @Column(nullable = false) private Instant dueAt;
    @Column(nullable = false, columnDefinition = "TEXT") private String checklistJson;
    @Column(nullable = false) private int reviewRound;
    @Column(length = 1000) private String appealReason;
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
        this.dueAt = Instant.now().plus(slaDays(reviewType), ChronoUnit.DAYS);
        this.checklistJson = "{}";
        this.reviewRound = 1;
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
        requestChanges(actor, reasonCode, feedback, null);
    }

    public void requestChanges(String actor, String reasonCode, String feedback, String evidenceUrl) {
        requireOpen();
        ReviewReasonCode.requireValid(reasonCode);
        requireHttpsEvidence(evidenceUrl);
        status = ReviewCaseStatus.CHANGES_REQUESTED;
        decidedBy = actor;
        this.reasonCode = reasonCode;
        this.externalFeedback = feedback;
        this.evidenceUrl = evidenceUrl;
        decidedAt = Instant.now();
    }

    public void assign(String actor) {
        requireOperational();
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "담당자 식별자가 필요합니다.");
        }
        assignedTo = actor;
        assignedAt = Instant.now();
    }

    public void updateChecklist(String checklistJson, String internalMemo) {
        requireOperational();
        if (checklistJson == null || checklistJson.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "체크리스트가 필요합니다.");
        }
        this.checklistJson = checklistJson;
        this.internalMemo = internalMemo;
    }

    public void block(String actor, String reasonCode, String internalMemo, String evidenceUrl) {
        requireOpen();
        ReviewReasonCode.requireValid(reasonCode);
        requireHttpsEvidence(evidenceUrl);
        status = ReviewCaseStatus.BLOCKED;
        decidedBy = actor;
        this.reasonCode = reasonCode;
        this.internalMemo = internalMemo;
        this.evidenceUrl = evidenceUrl;
        decidedAt = Instant.now();
    }

    public void cancel(String actor, String reasonCode, String internalMemo) {
        requireOpen();
        ReviewReasonCode.requireValid(reasonCode);
        status = ReviewCaseStatus.CANCELLED;
        decidedBy = actor;
        this.reasonCode = reasonCode;
        this.internalMemo = internalMemo;
        decidedAt = Instant.now();
    }

    public void expire() {
        requireOpen();
        status = ReviewCaseStatus.EXPIRED;
        decidedBy = "system:sla";
        reasonCode = ReviewReasonCode.SLA_EXPIRED.name();
        decidedAt = Instant.now();
    }

    public void appeal(String reason) {
        if (status != ReviewCaseStatus.CHANGES_REQUESTED && status != ReviewCaseStatus.BLOCKED
                && status != ReviewCaseStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.CONFLICT, "수정 요청·차단·만료된 심사만 재검토할 수 있습니다.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이의 제기 사유가 필요합니다.");
        }
        status = ReviewCaseStatus.REQUESTED;
        appealReason = reason;
        reasonCode = null;
        externalFeedback = null;
        decidedBy = null;
        decidedAt = null;
        dueAt = Instant.now().plus(slaDays(reviewType), ChronoUnit.DAYS);
        reviewRound++;
    }

    public boolean isOverdue(Instant now) {
        return (status == ReviewCaseStatus.REQUESTED || status == ReviewCaseStatus.EXTERNAL_SUBMITTED)
                && dueAt.isBefore(now);
    }

    private void requireOperational() {
        if (status == ReviewCaseStatus.CANCELLED || status == ReviewCaseStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.CONFLICT, "종료된 심사는 변경할 수 없습니다.");
        }
    }

    private void requireHttpsEvidence(String value) {
        if (value != null && !value.isBlank() && !value.matches("^https://\\S+$")) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "첨부 증빙은 HTTPS URL이어야 합니다.");
        }
    }

    private static long slaDays(ReviewType type) {
        return switch (type) {
            case BUILD_QA -> 2;
            case STORE_PAGE, SDK_COMPLIANCE -> 3;
            case RATING, LEGAL, COMMERCIAL -> 5;
        };
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
