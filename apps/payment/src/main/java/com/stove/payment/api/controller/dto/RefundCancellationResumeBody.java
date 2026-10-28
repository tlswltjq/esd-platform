package com.stove.payment.api.controller.dto;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.RefundCancellationResumeRequest;
import io.swagger.v3.oas.annotations.media.Schema;

public record RefundCancellationResumeBody(
        @Schema(format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) String requestId,
        @Schema(format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) String attemptId,
        @Schema(maxLength = 500, requiredMode = Schema.RequiredMode.REQUIRED, description = "확인 내용과 재개 사유. 공백만 입력할 수 없습니다.") String note) {
    public RefundCancellationResumeRequest toInput(String holdId, String operator) {
        try {
            return new RefundCancellationResumeRequest(requestId, attemptId, holdId, operator, note);
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
}
