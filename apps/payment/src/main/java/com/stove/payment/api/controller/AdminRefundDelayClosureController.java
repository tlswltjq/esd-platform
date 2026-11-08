package com.stove.payment.api.controller;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import com.stove.payment.api.controller.dto.RefundClosureBody;
import com.stove.payment.api.controller.dto.RefundClosureResponse;
import com.stove.payment.core.service.CloseRefundDelay;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator closure of a delayed or unattributed PortOne refund execution (decision 127). Only PortOne refunds are delay targets. */
@RestController
@ConditionalOnProperty(name = "stove.payment.pg.provider", havingValue = "PORTONE_TEST")
@SecurityRequirement(name = "oauth2", scopes = "commerce")
@RequestMapping("/api/v1/admin/refund-delays")
public class AdminRefundDelayClosureController {
    private final CloseRefundDelay close;

    public AdminRefundDelayClosureController(CloseRefundDelay close) { this.close = close; }

    @PostMapping("/{attemptId}/closure")
    @Operation(operationId = "closeRefundDelay", summary = "관리자 장기·귀속 미확정 환불 종결",
            description = "PG를 다시 조회해 같은 원결제의 전액 취소가 확인될 때만 닫습니다. 미확정 시도는 기존 조회 완료 경로로 즉시 완료하고, "
                    + "이미 완료된 귀속 미확정 시도는 판정만 기록합니다. 귀속 판정·ADMIN·근거·시각을 저장합니다. "
                    + "PG가 아직 결제 상태(잔액 있음)·부분 취소·진행 중이면 409 REFUND_CLOSURE_PG_NOT_CANCELLED(콘솔에서 먼저 취소)입니다. "
                    + "같은 requestId·내용은 최초 결과를 반환하고 다른 내용은 409입니다. PG 취소를 실행하지 않습니다.")
    public ResponseEntity<ApiResponse<RefundClosureResponse>> close(@CurrentSubject String operator,
            @PathVariable String attemptId, @RequestBody RefundClosureBody body) {
        RefundClosureResponse receipt = RefundClosureResponse.from(close.execute(body.toInput(attemptId, operator)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).location(URI.create("/api/v1/admin/refund-delays/" + receipt.attemptId()))
                .body(ApiResponse.ok(receipt));
    }
}
