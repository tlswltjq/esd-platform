package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record PaymentResponse(String paymentId,String orderNo,Long productId,String productCode,String title,
        long contentVersion,long listAmount,long discountAmount,long amount,String currency,String provider,PaymentStatus status,
        Instant createdAt,Instant updatedAt,Instant resultOccurredAt,String failureReasonCode,String failureReason,
        Long pgPaidAmount, Long pgDiscountAmount,
        @Schema(description="결제 상세에서 금융 보류 중이거나 보류 종결로 끝난 결제의 고객 안내(청구 여부별 코드·문구·보류 시작 시각). 그 밖의 결제와 목록에서는 null입니다.",nullable=true)
        ApprovalReviewNoticeResponse reviewNotice) {
    public static PaymentResponse from(Payment p) { return from(p, null); }
    public static PaymentResponse from(Payment p, ApprovalReviewNoticeResponse reviewNotice) {
        return new PaymentResponse(p.getPaymentId(),p.getOrderNo(),p.getProductId(),p.getProductCode(),p.getTitle(),
                p.getContentVersion(),p.getListAmount(),p.getDiscountAmount(),p.getAmount(),p.getCurrency(),p.getProvider(),p.getStatus(),
                p.getCreatedAt(),p.getUpdatedAt(),p.getResultOccurredAt(),p.getFailureReasonCode(),p.getFailureReason(),
                p.getPgPaidAmount(),p.getPgDiscountAmount(),reviewNotice);
    }
}
