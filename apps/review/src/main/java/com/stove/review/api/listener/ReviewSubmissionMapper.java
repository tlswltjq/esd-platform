package com.stove.review.api.listener;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.ProjectReviewRequestedEvent;
import com.stove.common.web.error.*;
import com.stove.review.core.domain.ReviewSubmission;
import java.util.Objects;

public final class ReviewSubmissionMapper {
    private ReviewSubmissionMapper() {}

    public static ReviewSubmission from(EventEnvelope envelope, ProjectReviewRequestedEvent event) {
        if (envelope == null || event == null || event.snapshot() == null
                || !Objects.equals(envelope.eventId(), event.eventId())
                || !Objects.equals(envelope.eventType(), event.eventType())
                || !Objects.equals(envelope.key(), event.snapshot().productCode()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "심사 요청 이벤트 계약이 올바르지 않습니다.");
        return new ReviewSubmission(event.eventId(), event.eventType(), event.occurredAt(),
                event.reviewRequestId(), event.snapshot());
    }
}
