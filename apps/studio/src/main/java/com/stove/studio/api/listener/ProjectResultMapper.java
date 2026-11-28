package com.stove.studio.api.listener;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.DomainEvent;
import com.stove.common.contract.payload.ProjectReleaseCompletedEvent;
import com.stove.common.contract.payload.ProjectReleaseRejectedEvent;
import com.stove.common.contract.payload.ProjectReviewApprovedEvent;
import com.stove.common.contract.payload.ProjectReviewRejectedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.studio.core.domain.release.ProjectReleaseResult;
import com.stove.studio.core.domain.review.ProjectReviewResult;
import java.util.Objects;

public final class ProjectResultMapper {
    private ProjectResultMapper() {}

    public static ProjectReviewResult from(EventMessage message, ProjectReviewApprovedEvent event) {
        validate(message, event);
        return new ProjectReviewResult(event.eventId(), event.eventType(), event.reviewRequestId(),
                event.projectId(), event.contentVersion(), true, null, event.actor());
    }

    public static ProjectReviewResult from(EventMessage message, ProjectReviewRejectedEvent event) {
        validate(message, event);
        return new ProjectReviewResult(event.eventId(), event.eventType(), event.reviewRequestId(),
                event.projectId(), event.contentVersion(), false, event.feedback(), event.actor());
    }

    public static ProjectReleaseResult from(EventMessage message, ProjectReleaseCompletedEvent event) {
        validate(message, event);
        return new ProjectReleaseResult(event.eventId(), event.eventType(), event.releaseRequestId(),
                event.projectId(), event.contentVersion(), true, null, event.requestEventId());
    }

    public static ProjectReleaseResult from(EventMessage message, ProjectReleaseRejectedEvent event) {
        validate(message, event);
        return new ProjectReleaseResult(event.eventId(), event.eventType(), event.releaseRequestId(),
                event.projectId(), event.contentVersion(), false, event.reason(), event.requestEventId());
    }

    private static void validate(EventMessage message, DomainEvent event) {
        if (message == null || event == null || event.occurredAt() == null
                || !Objects.equals(message.eventId(), event.eventId())
                || !Objects.equals(message.eventType(), event.eventType())
                || !Objects.equals(message.key(), event.partitionKey())
                || event.partitionKey() == null
                || !event.partitionKey().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}"))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "외부 결과 이벤트 계약이 올바르지 않습니다.");
    }
}
