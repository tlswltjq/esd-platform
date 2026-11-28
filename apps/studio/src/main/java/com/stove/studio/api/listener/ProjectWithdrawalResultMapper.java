package com.stove.studio.api.listener;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.DomainEvent;
import com.stove.common.contract.payload.ProjectReleaseWithdrawalResolvedEvent;
import com.stove.common.contract.payload.ProjectReviewWithdrawalResolvedEvent;
import com.stove.common.contract.payload.ProjectWithdrawalResult;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.studio.core.domain.project.ProjectRequestKind;
import com.stove.studio.core.domain.project.WithdrawalResolution;
import java.util.Objects;

public final class ProjectWithdrawalResultMapper {
    private ProjectWithdrawalResultMapper() {}

    public static WithdrawalResolution from(EventMessage message, ProjectReviewWithdrawalResolvedEvent event) {
        return convert(message, event, event == null ? null : event.result(), ProjectRequestKind.WITHDRAW_REVIEW);
    }

    public static WithdrawalResolution from(EventMessage message, ProjectReleaseWithdrawalResolvedEvent event) {
        return convert(message, event, event == null ? null : event.result(), ProjectRequestKind.WITHDRAW_RELEASE);
    }

    private static WithdrawalResolution convert(EventMessage message, DomainEvent event,
            ProjectWithdrawalResult result, ProjectRequestKind kind) {
        if (message == null || event == null || result == null
                || !Objects.equals(message.eventId(), event.eventId())
                || !Objects.equals(message.eventType(), event.eventType())
                || !Objects.equals(message.key(), result.productCode()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "철회 결과 이벤트 계약이 올바르지 않습니다.");
        return new WithdrawalResolution(event.eventId(), event.eventType(), event.occurredAt(), kind, result);
    }
}
