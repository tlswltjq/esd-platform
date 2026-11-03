package com.stove.review.api.listener;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.ProjectReviewWithdrawalRequestedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.review.core.domain.ReviewWithdrawalInput;
import java.util.Objects;

public final class ReviewWithdrawalMapper {
    private ReviewWithdrawalMapper() {}

    public static ReviewWithdrawalInput from(
            EventEnvelope envelope, ProjectReviewWithdrawalRequestedEvent event) {
        if (envelope == null || event == null || event.request() == null
                || !Objects.equals(envelope.eventId(), event.eventId())
                || !Objects.equals(envelope.eventType(), event.eventType())
                || !Objects.equals(envelope.key(), event.request().productCode()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "심사 철회 이벤트 계약이 올바르지 않습니다.");
        return new ReviewWithdrawalInput(event.eventId(), event.eventType(), event.occurredAt(), event.request());
    }
}
