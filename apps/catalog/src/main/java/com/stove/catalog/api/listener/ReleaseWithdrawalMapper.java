package com.stove.catalog.api.listener;

import com.stove.catalog.core.domain.ReleaseWithdrawalInput;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.ProjectReleaseWithdrawalRequestedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.util.Objects;

public final class ReleaseWithdrawalMapper {
    private ReleaseWithdrawalMapper() {}

    public static ReleaseWithdrawalInput from(
            EventEnvelope envelope, ProjectReleaseWithdrawalRequestedEvent event) {
        if (envelope == null || event == null || event.request() == null
                || !Objects.equals(envelope.eventId(), event.eventId())
                || !Objects.equals(envelope.eventType(), event.eventType())
                || !Objects.equals(envelope.key(), event.request().productCode()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "출시 철회 이벤트 계약이 올바르지 않습니다.");
        return new ReleaseWithdrawalInput(event.eventId(), event.eventType(), event.occurredAt(), event.request());
    }
}
