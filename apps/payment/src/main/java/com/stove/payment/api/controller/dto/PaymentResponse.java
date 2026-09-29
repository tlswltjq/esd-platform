package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentStatus;
import java.time.Instant;

public record PaymentResponse(
        Long paymentId,
        String orderNo,
        Long memberId,
        long amount,
        String currency,
        PaymentStatus status,
        String method,
        String pgTxId,
        Instant paidAt,
        String failReasonCode,
        String failReason,
        String cancelReason,
        boolean retryable
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getOrderNo(),
                payment.getMemberId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getMethod(),
                payment.getPgTxId(),
                payment.getPaidAt(),
                payment.getFailReasonCode(),
                payment.getFailReason(),
                payment.getCancelReason(),
                payment.getStatus() == PaymentStatus.READY
                        || payment.getStatus() == PaymentStatus.PENDING
                        || payment.getStatus() == PaymentStatus.CANCELING);
    }
}
