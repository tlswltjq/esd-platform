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
import com.stove.review.core.domain.SubmissionSnapshot;
import com.stove.review.core.domain.SubmissionSnapshotRepository;
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

    public void receive(String eventId, String eventType, SubmissionCreatedEvent event) {
        if (!processedEventGuard.firstDelivery(eventId, ReviewService.CONSUMER_GROUP, eventType)) return;
        if (snapshotRepository.existsById(event.submissionId())) return;
        snapshotRepository.save(SubmissionSnapshot.from(event));
        Arrays.stream(ReviewType.values())
                .map(type -> ReviewCase.requested(event.submissionId(), type))
                .forEach(caseRepository::save);
    }

    public void approve(Long caseId, String actor, ReviewCase.RatingDecision rating) {
        ReviewCase reviewCase = requireCase(caseId);
        SubmissionSnapshot snapshot = requireSnapshot(reviewCase.getSubmissionId());
        validateRatingPath(reviewCase, snapshot, rating);
        reviewCase.approve(actor, rating);
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

    private void validateRatingPath(ReviewCase reviewCase, SubmissionSnapshot snapshot,
                                    ReviewCase.RatingDecision rating) {
        if (reviewCase.getReviewType() != ReviewType.RATING || rating == null) {
            return;
        }
        if ("SELF_CLASSIFICATION".equals(snapshot.getRatingPath()) && "18".equals(rating.ratingCode())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "18세 등급은 GRAC 경로에서만 승인할 수 있습니다.");
        }
        if ("GRAC".equals(snapshot.getRatingPath()) && !"18".equals(rating.ratingCode())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "P0 GRAC 경로는 18세 등급 결과만 지원합니다.");
        }
    }
}
