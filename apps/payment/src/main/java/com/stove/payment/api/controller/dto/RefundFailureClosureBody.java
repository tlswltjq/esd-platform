package com.stove.payment.api.controller.dto;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.RefundFailureClosureRequest;
import io.swagger.v3.oas.annotations.media.Schema;

public record RefundFailureClosureBody(
        @Schema(format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED, description = "같은 실패 종결 요청의 재전송을 식별합니다.") String requestId,
        @Schema(maxLength = RefundFailureClosureRequest.NOTE_LIMIT, requiredMode = Schema.RequiredMode.REQUIRED,
                description = "중단 원인과 PG 콘솔에서 확인한 내용(예: 403 원인, 설정 수정). 공백만 입력할 수 없습니다.") String note) {
    public RefundFailureClosureRequest toInput(String attemptId, String operator) {
        try {
            return new RefundFailureClosureRequest(requestId, attemptId, operator, note);
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
}
