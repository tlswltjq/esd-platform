package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.RefundRetryRequest;
import io.swagger.v3.oas.annotations.media.Schema;

public record RefundRetryRequestBody(
        @Schema(description = "환불 조회에서 제공한 재시도 가능한 실패 시도 ID", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
        String previousAttemptId) {
    public RefundRetryRequest toInput(String buyer, String orderNo) {
        return new RefundRetryRequest(buyer, orderNo, previousAttemptId);
    }
}
