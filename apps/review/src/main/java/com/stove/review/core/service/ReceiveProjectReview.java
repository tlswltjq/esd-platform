package com.stove.review.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.contract.payload.*;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.web.error.*;
import com.stove.review.core.domain.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ReceiveProjectReview {
    public static final String CONSUMER_GROUP = "review-project";
    private final ProjectReviewRepository reviews;
    private final ProcessedEventGuard guard;
    private final ObjectMapper mapper;

    public void execute(ReviewSubmission submission) {
        if (!guard.firstDelivery(submission.eventId(), CONSUMER_GROUP, submission.eventType())) return;
        ProjectReview existing = reviews.findById(submission.reviewRequestId()).orElse(null);
        if (existing != null) {
            try {
                if (!submission.snapshot()
                        .equals(mapper.readValue(existing.getSnapshot(), ProjectSnapshot.class)))
                    throw new BusinessException(ErrorCode.CONFLICT, "동일 요청의 스냅샷이 다릅니다.");
            } catch (JsonProcessingException failure) {
                throw new IllegalStateException("저장된 심사 스냅샷을 읽을 수 없습니다.", failure);
            }
            return;
        }
        try {
            reviews.saveAndFlush(
                    ProjectReview.requested(submission, mapper.writeValueAsString(submission.snapshot())));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("심사 스냅샷 직렬화 실패", failure);
        }
    }
}
