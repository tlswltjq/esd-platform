package com.stove.review.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.ReviewChangesRequestedEvent;
import com.stove.common.event.payload.SubmissionCreatedEvent;
import com.stove.common.event.payload.SubmissionReviewApprovedEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.review.core.domain.ReviewCase;
import com.stove.review.core.domain.ReviewCaseRepository;
import com.stove.review.core.domain.ReviewType;
import com.stove.review.core.domain.RatingBoardSubmission;
import com.stove.review.core.domain.SubmissionSnapshot;
import com.stove.review.core.domain.SubmissionSnapshotRepository;
import com.stove.review.core.port.RatingBoardClient;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class SubmissionReviewService {

    private static final String AGGREGATE = "SubmissionReview";

    private final SubmissionSnapshotRepository snapshotRepository;
    private final ReviewCaseRepository caseRepository;
    private final ProcessedEventGuard processedEventGuard;
    private final OutboxRecorder outboxRecorder;
    private final AuditLogService auditLogService;
    private final RatingBoardClient ratingBoardClient;

    public void receive(String eventId, String eventType, SubmissionCreatedEvent event) {
        if (!processedEventGuard.firstDelivery(eventId, ReviewService.CONSUMER_GROUP, eventType)) return;
        if (snapshotRepository.existsById(event.submissionId())) return;
        snapshotRepository.save(SubmissionSnapshot.from(event));
        Arrays.stream(ReviewType.values())
                .map(type -> createCase(event, type))
                .forEach(caseRepository::save);
    }

    public void approve(Long caseId, String actor, ReviewCase.RatingDecision rating) {
        ReviewCase reviewCase = requireCase(caseId);
        SubmissionSnapshot snapshot = requireSnapshot(reviewCase.getSubmissionId());
        approveForPath(reviewCase, snapshot, rating, actor);
        outboxRecorder.record(AGGREGATE, snapshot.getProductCode(), SubmissionReviewApprovedEvent.of(
                reviewCase.getId(), snapshot.getSubmissionId(), snapshot.getBuildId(),
                snapshot.getMetadataRevision(), snapshot.getPricingRevision(), snapshot.getRatingRevision(),
                reviewCase.getReviewType().name(), snapshot.getProductCode(), reviewCase.getRatingCode(),
                reviewCase.getCertificationNumber(), reviewCase.getIssuer(), reviewCase.getIssuedAt(),
                reviewCase.getCountry()));
        auditLogService.record(actor, "REVIEW_APPROVED", reviewCase.getId(),
                "submissionId=" + snapshot.getSubmissionId() + ",type=" + reviewCase.getReviewType());
    }

    public void requestChanges(Long caseId, String actor, String reasonCode, String feedback) {
        ReviewCase reviewCase = requireCase(caseId);
        SubmissionSnapshot snapshot = requireSnapshot(reviewCase.getSubmissionId());
        reviewCase.requestChanges(actor, reasonCode, feedback);
        outboxRecorder.record(AGGREGATE, snapshot.getProductCode(), ReviewChangesRequestedEvent.of(
                reviewCase.getId(), snapshot.getSubmissionId(), reviewCase.getReviewType().name(),
                snapshot.getProductCode(), reasonCode, feedback));
        auditLogService.record(actor, "REVIEW_CHANGES_REQUESTED", reviewCase.getId(),
                "submissionId=" + snapshot.getSubmissionId() + ",reasonCode=" + reasonCode);
    }

    @Transactional(readOnly = true)
    public List<ReviewCase> cases(Long submissionId) {
        return submissionId == null ? caseRepository.findAll()
                : caseRepository.findBySubmissionIdOrderByReviewType(submissionId);
    }

    private ReviewCase requireCase(Long caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "reviewCaseId=" + caseId));
    }

    private SubmissionSnapshot requireSnapshot(Long submissionId) {
        return snapshotRepository.findById(submissionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "submissionId=" + submissionId));
    }

    private ReviewCase createCase(SubmissionCreatedEvent event, ReviewType type) {
        if (type != ReviewType.RATING) {
            return ReviewCase.requested(event.submissionId(), type);
        }
        validateRatingPolicyContext(event);
        if ("SELF_CLASSIFICATION".equals(event.ratingPath())) {
            return ReviewCase.selfClassification(event.submissionId(), event.targetRatingCode());
        }
        if (!"GRAC".equals(event.ratingPath())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지원하지 않는 등급 경로입니다: " + event.ratingPath());
        }
        String ticket = ratingBoardClient.submit(new RatingBoardSubmission(
                event.submissionId(), event.productCode(), event.title(), event.sellerId(), event.buildId(),
                event.productVersion(), event.ratingPolicyVersion(), event.ratingCountry(),
                event.targetRatingCode(), event.ratingQuestionnaire()));
        return ReviewCase.externalSubmitted(event.submissionId(), event.targetRatingCode(), ticket);
    }

    private void validateRatingPolicyContext(SubmissionCreatedEvent event) {
        if (!"KR".equals(event.ratingCountry()) || !"KR-2026-01".equals(event.ratingPolicyVersion())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지원하지 않는 등급 지역 또는 정책 버전입니다.");
        }
        boolean selfRating = "SELF_CLASSIFICATION".equals(event.ratingPath())
                && java.util.Set.of("ALL", "12", "15").contains(event.targetRatingCode());
        boolean gracRating = "GRAC".equals(event.ratingPath()) && "18".equals(event.targetRatingCode());
        if (!selfRating && !gracRating) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "등급 경로와 목표 등급이 일치하지 않습니다.");
        }
        if (event.ratingQuestionnaire() == null || event.ratingQuestionnaire().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "등급 설문 스냅샷이 필요합니다.");
        }
    }

    private void approveForPath(ReviewCase reviewCase, SubmissionSnapshot snapshot,
                                ReviewCase.RatingDecision rating, String actor) {
        if (reviewCase.getReviewType() != ReviewType.RATING) {
            if (rating != null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "등급 증빙은 등급 심사에만 입력할 수 있습니다.");
            }
            reviewCase.approve(actor);
            return;
        }
        if (rating == null || rating.ratingCode() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "등급 코드가 필요합니다.");
        }
        if ("SELF_CLASSIFICATION".equals(snapshot.getRatingPath())) {
            if (rating.certificationNumber() != null || rating.issuer() != null || rating.issuedAt() != null
                    || rating.country() != null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "자체등급 인증 증빙은 플랫폼이 발급하므로 등급 코드만 입력해야 합니다.");
            }
            reviewCase.approveSelfClassification(actor, rating.ratingCode(), snapshot.getRatingCountry());
            return;
        }
        if (!snapshot.getRatingCountry().equals(rating.country())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "등급 증빙의 대상 국가가 제출물과 다릅니다.");
        }
        reviewCase.approveExternalRating(actor, rating);
    }
}
