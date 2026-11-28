package com.stove.catalog.api.listener;

import com.stove.catalog.core.domain.ReleaseSubmission;
import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.ProjectReleaseRequestedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.util.Objects;

public final class ReleaseSubmissionMapper {
    private ReleaseSubmissionMapper() {}

    public static ReleaseSubmission from(EventMessage message, ProjectReleaseRequestedEvent event) {
        if (message == null || event == null || event.snapshot() == null
                || !Objects.equals(message.eventId(), event.eventId())
                || !Objects.equals(message.eventType(), event.eventType())
                || !Objects.equals(message.key(), event.snapshot().productCode()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "출시 요청 이벤트 계약이 올바르지 않습니다.");
        return new ReleaseSubmission(event.eventId(), event.eventType(), event.occurredAt(),
                event.releaseRequestId(), event.reviewRequestId(), event.snapshot());
    }
}
