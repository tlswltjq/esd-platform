package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.RefundReconsiderationRequest;
import io.swagger.v3.oas.annotations.media.Schema;

public record RefundReconsiderationBody(
        @Schema(format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) String requestId,
        @Schema(format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) String previousReviewId,
        @Schema(maxLength = 500, requiredMode = Schema.RequiredMode.REQUIRED, description = "재심사를 요청하는 사유. 공백만 입력할 수 없습니다.") String reason) {
    public RefundReconsiderationRequest toInput(String orderNo, String buyer) {
        return new RefundReconsiderationRequest(requestId, orderNo, previousReviewId, buyer, reason);
    }
}
