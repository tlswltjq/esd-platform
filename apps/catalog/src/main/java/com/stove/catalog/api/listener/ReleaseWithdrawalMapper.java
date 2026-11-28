package com.stove.catalog.api.listener;

import com.stove.catalog.core.domain.ReleaseWithdrawalInput;
import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.ProjectReleaseWithdrawalRequestedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.util.Objects;

public final class ReleaseWithdrawalMapper {
    private ReleaseWithdrawalMapper() {}

    public static ReleaseWithdrawalInput from(
            EventMessage message, ProjectReleaseWithdrawalRequestedEvent event) {
        if (message == null || event == null || event.request() == null
                || !Objects.equals(message.eventId(), event.eventId())
                || !Objects.equals(message.eventType(), event.eventType())
                || !Objects.equals(message.key(), event.request().productCode()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "출시 철회 이벤트 계약이 올바르지 않습니다.");
        return new ReleaseWithdrawalInput(event.eventId(), event.eventType(), event.occurredAt(), event.request());
    }
}
