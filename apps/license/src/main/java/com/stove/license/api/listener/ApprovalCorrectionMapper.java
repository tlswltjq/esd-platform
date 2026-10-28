package com.stove.license.api.listener;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.LicenseApprovalCorrectionRequestedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.license.core.domain.ApprovalCorrectionRequest;
import java.util.Objects;

public final class ApprovalCorrectionMapper {
    private ApprovalCorrectionMapper() { }
    public static ApprovalCorrectionRequest from(EventEnvelope envelope, LicenseApprovalCorrectionRequestedEvent event) {
        if (envelope==null || event==null || !Objects.equals(envelope.eventId(),event.eventId())
                || !Objects.equals(envelope.eventType(),event.eventType()) || !Objects.equals(envelope.key(),event.orderNo()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST,"승인 정정 봉투·본문·키가 다릅니다.");
        return new ApprovalCorrectionRequest(event.eventId(),event.occurredAt(),event.refundId(),event.reviewId(),event.stopRequestId(),event.licenseId(),event.orderNo(),event.buyerSubject(),event.productId(),event.productCode(),event.paymentId(),event.suspensionRequestEventId(),event.suspensionEventId());
    }
}
