package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentStatus;
import java.time.Instant;

public record PaymentResponse(String paymentId,String orderNo,Long productId,String productCode,String title,
        long contentVersion,long listAmount,long discountAmount,long amount,String currency,String provider,PaymentStatus status,
        Instant createdAt,Instant updatedAt,Instant resultOccurredAt,String failureReasonCode,String failureReason) {
    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(p.getPaymentId(),p.getOrderNo(),p.getProductId(),p.getProductCode(),p.getTitle(),
                p.getContentVersion(),p.getListAmount(),p.getDiscountAmount(),p.getAmount(),p.getCurrency(),p.getProvider(),p.getStatus(),
                p.getCreatedAt(),p.getUpdatedAt(),p.getResultOccurredAt(),p.getFailureReasonCode(),p.getFailureReason());
    }
}
