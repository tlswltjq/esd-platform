package com.stove.payment.api.controller;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import com.stove.payment.api.controller.dto.RefundClosureBody;
import com.stove.payment.api.controller.dto.RefundClosureResponse;
import com.stove.payment.api.controller.dto.RefundFailureClosureBody;
import com.stove.payment.api.controller.dto.RefundFailureClosureResponse;
import com.stove.payment.core.service.CloseRefundDelay;
import com.stove.payment.core.service.CloseRefundFailure;
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

/**
 * Operator closures of PortOne refund executions: of a delayed or unattributed one on a full cancellation (decision 127),
 * and of a stopped one as failed when the PG shows that no money moved (decision 128). Only PortOne refunds are targets.
 */
@RestController
@ConditionalOnProperty(name = "stove.payment.pg.provider", havingValue = "PORTONE_TEST")
@SecurityRequirement(name = "oauth2", scopes = "commerce")
@RequestMapping("/api/v1/admin/refund-delays")
public class AdminRefundDelayClosureController {
    private final CloseRefundDelay close;
    private final CloseRefundFailure fail;

    public AdminRefundDelayClosureController(CloseRefundDelay close, CloseRefundFailure fail) { this.close = close; this.fail = fail; }

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

    @PostMapping("/{attemptId}/failure-closure")
    @Operation(operationId = "closeRefundFailure", summary = "관리자 자동 송신이 멈춘 환불의 실패 종결",
            description = "자동 취소 POST가 멈춘 시도(403·400 중단, 잔액 변화 차단, 최초 준비 후 3시간 경과)만 대상입니다. PG를 다시 조회해 원결제가 PAID이고 "
                    + "취소액이 0이며 목록의 취소가 모두 FAILED(진행 중·성공 취소 없음)일 때만 실패로 닫습니다. 근거 종류·중단 원인·PG 조회 스냅샷 해시·FAILED 취소 ID·ADMIN·시각을 저장하고 "
                    + "기존 RefundExecutionFailed를 보내 Order가 보유권 복구와 고객 재신청으로 잇습니다. 취소액이 있거나 REQUESTED·SUCCEEDED 취소가 있으면 409 "
                    + "REFUND_FAILURE_CLOSURE_PG_NOT_UNCANCELLED, 대상이 아니면 409 REFUND_NOT_FAILURE_CLOSABLE입니다. 같은 requestId·내용은 최초 응답을 그대로 반환하고 "
                    + "다른 내용은 409입니다. 실패 뒤에도 PG를 낮은 빈도로 조회해 늦은 전액 취소를 조사 목록(kind=LATE_CANCELLATION)·경보로 올리며 상태·보유권은 바꾸지 않습니다. "
                    + "PG 취소를 실행하지 않습니다.")
    public ResponseEntity<ApiResponse<RefundFailureClosureResponse>> fail(@CurrentSubject String operator,
            @PathVariable String attemptId, @RequestBody RefundFailureClosureBody body) {
        RefundFailureClosureResponse receipt = RefundFailureClosureResponse.from(fail.execute(body.toInput(attemptId, operator)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).location(URI.create("/api/v1/admin/refund-delays/" + receipt.attemptId()))
                .body(ApiResponse.ok(receipt));
    }
}
