package com.stove.payment.api.controller.dto;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.ApprovalInvestigationRequest;
import io.swagger.v3.oas.annotations.media.Schema;

public record ApprovalInvestigationBody(
        @Schema(format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) String requestId,
        @Schema(maxLength = 500, requiredMode = Schema.RequiredMode.REQUIRED, description = "확인한 사실과 조사 내용. 공백만 입력할 수 없습니다.") String note) {
    public ApprovalInvestigationRequest toInput(String approvalId, String operator) {
        try {
            return new ApprovalInvestigationRequest(requestId, approvalId, operator, note);
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
}
