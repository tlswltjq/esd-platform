package com.stove.studio.api.listener;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.payload.ProjectReleaseWithdrawalResolvedEvent;
import com.stove.common.kafka.contract.payload.ProjectReviewWithdrawalResolvedEvent;
import com.stove.common.kafka.contract.payload.ProjectWithdrawalResult;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.studio.core.domain.project.ProjectRequestKind;
import com.stove.studio.core.domain.project.WithdrawalResolution;
import java.util.Objects;

public final class ProjectWithdrawalResultMapper {
    private ProjectWithdrawalResultMapper() {}

    public static WithdrawalResolution from(EventEnvelope envelope, ProjectReviewWithdrawalResolvedEvent event) {
        return convert(envelope, event, event == null ? null : event.result(), ProjectRequestKind.WITHDRAW_REVIEW);
    }

    public static WithdrawalResolution from(EventEnvelope envelope, ProjectReleaseWithdrawalResolvedEvent event) {
        return convert(envelope, event, event == null ? null : event.result(), ProjectRequestKind.WITHDRAW_RELEASE);
    }

    private static WithdrawalResolution convert(EventEnvelope envelope, DomainEvent event,
            ProjectWithdrawalResult result, ProjectRequestKind kind) {
        if (envelope == null || event == null || result == null
                || !Objects.equals(envelope.eventId(), event.eventId())
                || !Objects.equals(envelope.eventType(), event.eventType())
                || !Objects.equals(envelope.key(), result.productCode()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "철회 결과 이벤트 계약이 올바르지 않습니다.");
        return new WithdrawalResolution(event.eventId(), event.eventType(), event.occurredAt(), kind, result);
    }
}
