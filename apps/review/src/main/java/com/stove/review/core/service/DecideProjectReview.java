package com.stove.review.core.service;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.payload.*;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.*;
import com.stove.review.core.domain.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class DecideProjectReview {
    private final ProjectReviewRepository reviews;
    private final OutboxRecorder outbox;

    public ProjectReview execute(Long id, String subject, ReviewStatus decision, String feedback) {
        ProjectReview review = reviews.lock(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (review.decide(decision, subject, feedback)) {
            DomainEvent event =
                    decision == ReviewStatus.APPROVED
                            ? ProjectReviewApprovedEvent.of(
                                    id,
                                    review.getProjectId(),
                                    review.getContentVersion(),
                                    review.getProductCode(),
                                    subject)
                            : ProjectReviewRejectedEvent.of(
                                    id,
                                    review.getProjectId(),
                                    review.getContentVersion(),
                                    review.getProductCode(),
                                    feedback,
                                    subject);
            outbox.record("ProjectReview", review.getProductCode(), event);
        }
        return review;
    }
}
