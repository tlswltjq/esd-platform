package com.stove.review.api.listener;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.ProjectReviewRequestedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.review.core.domain.ReviewSubmission;
import java.util.Objects;

public final class ReviewSubmissionMapper {
    private ReviewSubmissionMapper() {}

    public static ReviewSubmission from(EventMessage message, ProjectReviewRequestedEvent event) {
        if (message == null || event == null || event.snapshot() == null
                || !Objects.equals(message.eventId(), event.eventId())
                || !Objects.equals(message.eventType(), event.eventType())
                || !Objects.equals(message.key(), event.snapshot().productCode()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "심사 요청 이벤트 계약이 올바르지 않습니다.");
        return new ReviewSubmission(event.eventId(), event.eventType(), event.occurredAt(),
                event.reviewRequestId(), event.snapshot());
    }
}
