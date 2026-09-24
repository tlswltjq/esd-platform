package com.stove.studio.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.ReviewChangesRequestedEvent;
import com.stove.common.event.payload.SubmissionReviewApprovedEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.studio.core.domain.Submission;
import com.stove.studio.core.domain.SubmissionGate;
import com.stove.studio.core.domain.SubmissionGateRepository;
import com.stove.studio.core.domain.SubmissionGateStatus;
import com.stove.studio.core.domain.SubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class SubmissionReviewProjectionService {

    private final SubmissionRepository submissionRepository;
    private final SubmissionGateRepository gateRepository;
    private final ProcessedEventGuard processedEventGuard;

    public void approve(String eventId, String eventType, SubmissionReviewApprovedEvent event) {
        if (!processedEventGuard.firstDelivery(eventId, GameProjectService.CONSUMER_GROUP, eventType)) return;
        Submission submission = requireSubmission(event.submissionId());
        requireSameSnapshot(submission, event);
        SubmissionGate gate = requireGate(event.submissionId(), event.reviewType());
        gate.approve();
        if ("RATING".equals(event.reviewType())) {
            submission.applyRating(event.ratingCode(), event.certificationNumber(), event.issuer(),
                    event.issuedAt(), event.country(), event.ratingPath(), event.ratingPolicyVersion(),
                    event.externalApplicationNumber(), event.externalEvidenceUrl());
        }
        boolean allApproved = gateRepository.findBySubmissionId(event.submissionId()).stream()
                .allMatch(value -> value.getStatus() == SubmissionGateStatus.APPROVED);
        if (allApproved) submission.readyForRelease();
    }

    public void changesRequested(String eventId, String eventType, ReviewChangesRequestedEvent event) {
        if (!processedEventGuard.firstDelivery(eventId, GameProjectService.CONSUMER_GROUP, eventType)) return;
        Submission submission = requireSubmission(event.submissionId());
        requireGate(event.submissionId(), event.reviewType()).changesRequested(event.reasonCode(), event.feedback());
        submission.changesRequested();
    }

    private Submission requireSubmission(Long id) {
        return submissionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "submissionId=" + id));
    }

    private SubmissionGate requireGate(Long submissionId, String type) {
        return gateRepository.findBySubmissionIdAndReviewType(submissionId, type)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "reviewType=" + type));
    }

    private void requireSameSnapshot(Submission submission, SubmissionReviewApprovedEvent event) {
        boolean same = submission.getBuildId().equals(event.buildId())
                && submission.getMetadataRevisionId().equals(event.metadataRevision())
                && submission.getPricingRevisionId().equals(event.pricingRevision())
                && submission.getRatingRevisionId().equals(event.ratingRevision());
        if (!same) throw new BusinessException(ErrorCode.CONFLICT, "심사 결과의 제출 스냅샷이 일치하지 않습니다.");
    }
}
