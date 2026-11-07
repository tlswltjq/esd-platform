package com.stove.payment.api.controller.dto;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.ApprovalClosureRequest;
import io.swagger.v3.oas.annotations.media.Schema;

public record ApprovalClosureBody(
        @Schema(format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED, description = "같은 종결 요청의 재전송을 식별합니다.") String requestId,
        @Schema(maxLength = ApprovalClosureRequest.NOTE_LIMIT, requiredMode = Schema.RequiredMode.REQUIRED,
                description = "PG 콘솔에서 확인·처리한 내용과 종결 이유. 공백만 입력할 수 없습니다.") String note) {
    public ApprovalClosureRequest toInput(String approvalId, String operator) {
        try {
            return new ApprovalClosureRequest(requestId, approvalId, operator, note);
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
}
