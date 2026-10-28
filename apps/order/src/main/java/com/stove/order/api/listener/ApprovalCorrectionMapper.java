package com.stove.order.api.listener;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.LicenseApprovalCorrectedEvent;
import com.stove.order.core.domain.ApprovalCorrectionConfirmation;
import com.stove.order.core.domain.RefundCompletionRejected;
import java.util.Objects;

public final class ApprovalCorrectionMapper {
    private ApprovalCorrectionMapper() { }
    public static ApprovalCorrectionConfirmation from(EventEnvelope envelope, LicenseApprovalCorrectedEvent event) {
        if (envelope==null || event==null || !Objects.equals(envelope.eventId(),event.eventId())
                || !Objects.equals(envelope.eventType(),event.eventType()) || !Objects.equals(envelope.key(),event.orderNo()))
            throw new RefundCompletionRejected("승인 정정 복구 봉투·본문·키가 다릅니다.");
        return new ApprovalCorrectionConfirmation(event.eventId(),event.occurredAt(),event.requestEventId(),event.refundId(),event.reviewId(),
                event.stopRequestId(),event.licenseId(),event.orderNo(),event.buyerSubject(),event.productId(),event.productCode(),event.paymentId(),
                event.suspensionRequestEventId(),event.suspensionEventId());
    }
}
