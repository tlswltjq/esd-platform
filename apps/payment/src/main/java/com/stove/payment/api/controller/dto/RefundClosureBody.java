package com.stove.payment.api.controller.dto;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.RefundAttribution;
import com.stove.payment.core.domain.RefundClosureRequest;
import io.swagger.v3.oas.annotations.media.Schema;

public record RefundClosureBody(
        @Schema(format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED, description = "같은 종결 요청의 재전송을 식별합니다.") String requestId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"OUR_REQUEST", "EXTERNAL_CANCELLATION", "UNDETERMINED"},
                description = "전액 취소의 귀속 판정: 우리 요청 / 외부·콘솔 취소 / 판단 불가. 기록만 하며 금액·상태를 바꾸지 않습니다.") String attribution,
        @Schema(maxLength = RefundClosureRequest.NOTE_LIMIT, requiredMode = Schema.RequiredMode.REQUIRED,
                description = "PG 콘솔에서 확인·처리한 내용과 판정 근거. 공백만 입력할 수 없습니다.") String note) {
    public RefundClosureRequest toInput(String attemptId, String operator) {
        try {
            return new RefundClosureRequest(requestId, attemptId, operator, RefundAttribution.parse(attribution), note);
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
}
