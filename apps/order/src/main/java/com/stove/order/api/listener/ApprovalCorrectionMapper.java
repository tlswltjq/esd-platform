package com.stove.order.api.listener;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.LicenseApprovalCorrectedEvent;
import com.stove.order.core.domain.ApprovalCorrectionConfirmation;
import com.stove.order.core.domain.RefundCompletionRejected;
import java.util.Objects;

public final class ApprovalCorrectionMapper {
    private ApprovalCorrectionMapper() { }
    public static ApprovalCorrectionConfirmation from(EventMessage message, LicenseApprovalCorrectedEvent event) {
        if (message==null || event==null || !Objects.equals(message.eventId(),event.eventId())
                || !Objects.equals(message.eventType(),event.eventType()) || !Objects.equals(message.key(),event.orderNo()))
            throw new RefundCompletionRejected("승인 정정 복구 헤더·본문·키가 다릅니다.");
        return new ApprovalCorrectionConfirmation(event.eventId(),event.occurredAt(),event.requestEventId(),event.refundId(),event.reviewId(),
                event.stopRequestId(),event.licenseId(),event.orderNo(),event.buyerSubject(),event.productId(),event.productCode(),event.paymentId(),
                event.suspensionRequestEventId(),event.suspensionEventId(), event.returnedToStorage());
    }
}
