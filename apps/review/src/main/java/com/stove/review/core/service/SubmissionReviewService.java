package com.stove.review.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.ReviewChangesRequestedEvent;
import com.stove.common.event.payload.ReviewAppealedEvent;
import com.stove.common.event.payload.SubmissionCreatedEvent;
import com.stove.common.event.payload.SubmissionReviewApprovedEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.review.core.domain.ReviewCase;
import com.stove.review.core.domain.ReviewCaseRepository;
import com.stove.review.core.domain.ReviewCaseStatus;
import com.stove.review.core.domain.ReviewDecisionHistory;
import com.stove.review.core.domain.ReviewDecisionHistoryRepository;
import com.stove.review.core.domain.ReviewType;
import com.stove.review.core.domain.SubmissionSnapshot;
import com.stove.review.core.domain.SubmissionSnapshotRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class SubmissionReviewService {

    private static final String AGGREGATE = "SubmissionReview";
    private static final String ACTIVE_COUNTRY = "KR";
    private static final String ACTIVE_POLICY_VERSION = "KR-2026-01";

    private final SubmissionSnapshotRepository snapshotRepository;
    private final ReviewCaseRepository caseRepository;
    private final ProcessedEventGuard processedEventGuard;
    private final OutboxRecorder outboxRecorder;
    private final AuditLogService auditLogService;
    private final ReviewDecisionHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;

    public void receive(String eventId, String eventType, SubmissionCreatedEvent event) {
        if (!processedEventGuard.firstDelivery(eventId, ReviewService.CONSUMER_GROUP, eventType)) return;
        if (snapshotRepository.existsById(event.submissionId())) return;
        validateRatingPolicyContext(event);
        snapshotRepository.save(SubmissionSnapshot.from(event));
        Arrays.stream(ReviewType.values()).forEach(type -> {
            ReviewCase reviewCase = caseRepository.save(createCase(event, type));
            history(reviewCase, "CREATED", "system:submission", null,
                    "submissionId=" + event.submissionId());
        });
    }

    public void approve(Long caseId, String actor, ReviewCase.RatingDecision rating) {
        ReviewCase reviewCase = requireCase(caseId);
        SubmissionSnapshot snapshot = requireSnapshot(reviewCase.getSubmissionId());
        ReviewCaseStatus before = reviewCase.getStatus();
        approveForPath(reviewCase, snapshot, rating, actor);
        outboxRecorder.record(AGGREGATE, snapshot.getProductCode(), SubmissionReviewApprovedEvent.of(
                reviewCase.getId(), snapshot.getSubmissionId(), snapshot.getBuildId(),
                snapshot.getMetadataRevision(), snapshot.getPricingRevision(), snapshot.getRatingRevision(),
                reviewCase.getReviewType().name(), snapshot.getProductCode(), reviewCase.getRatingCode(),
                reviewCase.getCertificationNumber(), reviewCase.getIssuer(), reviewCase.getIssuedAt(),
                reviewCase.getCountry(), snapshot.getRatingPath(), snapshot.getRatingPolicyVersion(),
                reviewCase.getExternalApplicationNumber(), reviewCase.getExternalEvidenceUrl()));
        auditLogService.record(actor, "REVIEW_APPROVED", reviewCase.getId(),
                "submissionId=" + snapshot.getSubmissionId() + ",type=" + reviewCase.getReviewType());
        history(reviewCase, "APPROVED", actor, before, null);
    }

    public void submitExternal(Long caseId, String actor, String applicationNumber,
                               Instant submittedAt, String evidenceUrl) {
        ReviewCase reviewCase = requireCase(caseId);
        SubmissionSnapshot snapshot = requireSnapshot(reviewCase.getSubmissionId());
        if (reviewCase.getReviewType() != ReviewType.RATING || !"GRAC".equals(snapshot.getRatingPath())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "GRAC 경로의 등급 심사만 외부 접수할 수 있습니다.");
        }
        ReviewCaseStatus before = reviewCase.getStatus();
        reviewCase.submitExternal(applicationNumber, submittedAt, evidenceUrl);
        auditLogService.record(actor, "RATING_EXTERNAL_SUBMITTED", reviewCase.getId(),
                "submissionId=" + snapshot.getSubmissionId() + ",applicationNumber=" + applicationNumber);
        history(reviewCase, "EXTERNAL_SUBMITTED", actor, before,
                "applicationNumber=" + applicationNumber);
    }

    public void requestChanges(Long caseId, String actor, String reasonCode, String feedback) {
        requestChanges(caseId, actor, reasonCode, feedback, null);
    }

    public void requestChanges(Long caseId, String actor, String reasonCode, String feedback,
                               String evidenceUrl) {
        ReviewCase reviewCase = requireCase(caseId);
        SubmissionSnapshot snapshot = requireSnapshot(reviewCase.getSubmissionId());
        ReviewCaseStatus before = reviewCase.getStatus();
        reviewCase.requestChanges(actor, reasonCode, feedback, evidenceUrl);
        outboxRecorder.record(AGGREGATE, snapshot.getProductCode(), ReviewChangesRequestedEvent.of(
                reviewCase.getId(), snapshot.getSubmissionId(), reviewCase.getReviewType().name(),
                snapshot.getProductCode(), reasonCode, feedback));
        auditLogService.record(actor, "REVIEW_CHANGES_REQUESTED", reviewCase.getId(),
                "submissionId=" + snapshot.getSubmissionId() + ",reasonCode=" + reasonCode);
        history(reviewCase, "CHANGES_REQUESTED", actor, before, reasonCode);
    }

    @Transactional(readOnly = true)
    public List<ReviewCase> cases(Long submissionId) {
        return submissionId == null ? caseRepository.findAll()
                : caseRepository.findBySubmissionIdOrderByReviewType(submissionId);
    }

    @Transactional(readOnly = true)
    public Page<ReviewCase> search(Long submissionId, ReviewType reviewType, ReviewCaseStatus status,
                                   String assignee, Boolean overdue, Pageable pageable) {
        Instant now = Instant.now();
        Specification<ReviewCase> specification = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (submissionId != null) predicates.add(builder.equal(root.get("submissionId"), submissionId));
            if (reviewType != null) predicates.add(builder.equal(root.get("reviewType"), reviewType));
            if (status != null) predicates.add(builder.equal(root.get("status"), status));
            if (assignee != null && !assignee.isBlank()) {
                predicates.add(builder.equal(root.get("assignedTo"), assignee));
            }
            if (Boolean.TRUE.equals(overdue)) {
                predicates.add(root.get("status").in(
                        ReviewCaseStatus.REQUESTED, ReviewCaseStatus.EXTERNAL_SUBMITTED));
                predicates.add(builder.lessThan(root.get("dueAt"), now));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        return caseRepository.findAll(specification, pageable);
    }

    public ReviewCase assign(Long caseId, String actor, String assignee, Long expectedVersion) {
        ReviewCase reviewCase = requireVersion(caseId, expectedVersion);
        ReviewCaseStatus before = reviewCase.getStatus();
        reviewCase.assign(assignee);
        history(reviewCase, "ASSIGNED", actor, before, "assignee=" + assignee);
        return reviewCase;
    }

    public ReviewCase updateChecklist(Long caseId, String actor, Map<String, Boolean> checklist,
                                      String internalMemo, Long expectedVersion) {
        ReviewCase reviewCase = requireVersion(caseId, expectedVersion);
        try {
            reviewCase.updateChecklist(objectMapper.writeValueAsString(checklist), internalMemo);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "체크리스트를 저장할 수 없습니다.");
        }
        history(reviewCase, "CHECKLIST_UPDATED", actor, reviewCase.getStatus(), null);
        return reviewCase;
    }

    public ReviewCase block(Long caseId, String actor, String reasonCode, String internalMemo,
                            String evidenceUrl, Long expectedVersion) {
        ReviewCase reviewCase = requireVersion(caseId, expectedVersion);
        ReviewCaseStatus before = reviewCase.getStatus();
        reviewCase.block(actor, reasonCode, internalMemo, evidenceUrl);
        history(reviewCase, "BLOCKED", actor, before, reasonCode);
        auditLogService.record(actor, "REVIEW_BLOCKED", caseId, "reasonCode=" + reasonCode);
        return reviewCase;
    }

    public ReviewCase cancel(Long caseId, String actor, String reasonCode, String internalMemo,
                             Long expectedVersion) {
        ReviewCase reviewCase = requireVersion(caseId, expectedVersion);
        ReviewCaseStatus before = reviewCase.getStatus();
        reviewCase.cancel(actor, reasonCode, internalMemo);
        history(reviewCase, "CANCELLED", actor, before, reasonCode);
        auditLogService.record(actor, "REVIEW_CANCELLED", caseId, "reasonCode=" + reasonCode);
        return reviewCase;
    }

    public ReviewCase appeal(Long caseId, String actor, String reason, Long expectedVersion) {
        ReviewCase reviewCase = requireVersion(caseId, expectedVersion);
        SubmissionSnapshot snapshot = requireSnapshot(reviewCase.getSubmissionId());
        ReviewCaseStatus before = reviewCase.getStatus();
        reviewCase.appeal(reason);
        outboxRecorder.record(AGGREGATE, snapshot.getProductCode(), ReviewAppealedEvent.of(
                reviewCase.getId(), snapshot.getSubmissionId(), reviewCase.getReviewType().name(),
                snapshot.getProductCode(), reason));
        history(reviewCase, "APPEALED", actor, before, reason);
        auditLogService.record(actor, "REVIEW_APPEALED", caseId, reason);
        return reviewCase;
    }

    @Transactional(readOnly = true)
    public List<ReviewDecisionHistory> history(Long caseId) {
        requireCase(caseId);
        return historyRepository.findByReviewCaseIdOrderByIdAsc(caseId);
    }

    public int expireDue() {
        List<ReviewCase> cases = caseRepository.findTop100ByStatusInAndDueAtBeforeOrderByDueAtAsc(
                List.of(ReviewCaseStatus.REQUESTED, ReviewCaseStatus.EXTERNAL_SUBMITTED), Instant.now());
        cases.forEach(reviewCase -> {
            ReviewCaseStatus before = reviewCase.getStatus();
            reviewCase.expire();
            history(reviewCase, "EXPIRED", "system:sla", before, null);
        });
        return cases.size();
    }

    private ReviewCase requireCase(Long caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "reviewCaseId=" + caseId));
    }

    private ReviewCase requireVersion(Long caseId, Long expectedVersion) {
        ReviewCase reviewCase = requireCase(caseId);
        if (expectedVersion != null && reviewCase.getEntityVersion() != expectedVersion) {
            throw new BusinessException(ErrorCode.CONFLICT, "심사 정보가 다른 담당자에 의해 변경되었습니다.");
        }
        return reviewCase;
    }

    private SubmissionSnapshot requireSnapshot(Long submissionId) {
        return snapshotRepository.findById(submissionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "submissionId=" + submissionId));
    }

    private ReviewCase createCase(SubmissionCreatedEvent event, ReviewType type) {
        if (type != ReviewType.RATING) return ReviewCase.requested(event.submissionId(), type);
        return ReviewCase.ratingRequested(event.submissionId(), event.ratingPath(),
                event.recommendedRatingCode());
    }

    private void history(ReviewCase reviewCase, String action, String actor,
                         ReviewCaseStatus before, String details) {
        historyRepository.save(ReviewDecisionHistory.record(reviewCase.getId(), action, actor,
                before, reviewCase.getStatus(), details));
    }

    private void validateRatingPolicyContext(SubmissionCreatedEvent event) {
        if (!ACTIVE_COUNTRY.equals(event.ratingCountry())
                || !ACTIVE_POLICY_VERSION.equals(event.ratingPolicyVersion())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "지원하지 않는 등급 지역 또는 정책 버전입니다.");
        }
        boolean selfRating = "SELF_CLASSIFICATION".equals(event.ratingPath())
                && Set.of("ALL", "12", "15").contains(event.recommendedRatingCode());
        boolean gracRating = "GRAC".equals(event.ratingPath())
                && "18".equals(event.recommendedRatingCode());
        if (!selfRating && !gracRating) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "등급 경로와 정책 결정 등급이 일치하지 않습니다.");
        }
        if (event.ratingQuestionnaire() == null || event.ratingQuestionnaire().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "등급 설문 스냅샷이 필요합니다.");
        }
    }

    private void approveForPath(ReviewCase reviewCase, SubmissionSnapshot snapshot,
                                ReviewCase.RatingDecision rating, String actor) {
        if (reviewCase.getReviewType() != ReviewType.RATING) {
            if (rating != null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "등급 증빙은 등급 심사에만 입력할 수 있습니다.");
            }
            reviewCase.approve(actor);
            return;
        }
        if (rating == null || rating.ratingCode() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "등급 코드가 필요합니다.");
        }
        if (!snapshot.getRecommendedRatingCode().equals(rating.ratingCode())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "정책 결정 등급과 승인 등급이 일치하지 않습니다.");
        }
        if ("SELF_CLASSIFICATION".equals(snapshot.getRatingPath())) {
            if (rating.certificationNumber() != null || rating.issuer() != null
                    || rating.issuedAt() != null || rating.country() != null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "자체등급 인증 증빙은 플랫폼이 발급하므로 등급 코드만 입력해야 합니다.");
            }
            reviewCase.approveSelfClassification(actor, rating.ratingCode(), snapshot.getRatingCountry());
            return;
        }
        if (!snapshot.getRatingCountry().equals(rating.country())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "등급 증빙의 대상 국가가 제출물과 다릅니다.");
        }
        reviewCase.approveExternalRating(actor, rating);
    }
}
