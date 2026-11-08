package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.RefundFailureReason;
import io.swagger.v3.oas.annotations.media.Schema;

/** 결정 134: 실패한 환불(FAILED)의 고객용 사유 코드와 재신청 안내. 알림은 보내지 않는다(결정 98). */
public record RefundFailureNoticeResponse(
        @Schema(description = "고객용 환불 실패 사유. PAYMENT_NOT_CANCELLED: 결제 취소가 처리되지 않아 환불되지 않음") Code code,
        String message) {
    public enum Code { PAYMENT_NOT_CANCELLED }

    public static RefundFailureNoticeResponse from(RefundFailureReason reason) {
        if (reason == null) return null;
        return switch (reason) {
            case PAYMENT_NOT_CANCELLED -> new RefundFailureNoticeResponse(Code.PAYMENT_NOT_CANCELLED,
                    "결제 취소가 처리되지 않아 환불되지 않았습니다. 다시 신청하면 환불됩니다.");
        };
    }
}
