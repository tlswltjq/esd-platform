package com.stove.order.api.controller;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import com.stove.order.api.controller.dto.AdminRefundDecisionResponse;
import com.stove.order.api.controller.dto.AdminRefundPage;
import com.stove.order.api.controller.dto.AdminRefundResponse;
import com.stove.order.api.controller.dto.RefundReviewRequestBody;
import com.stove.order.core.domain.RefundStatus;
import com.stove.order.core.service.DecideRefund;
import com.stove.order.core.service.ReadRefundReviews;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequiredArgsConstructor
@SecurityRequirement(name = "oauth2", scopes = "commerce")
@RequestMapping("/api/v1/admin/refunds")
public class AdminRefundController {
    private final ReadRefundReviews read;
    private final DecideRefund decide;

    @GetMapping
    @Operation(operationId = "searchAdminRefunds", summary = "관리자 환불 목록", description = "기본값은 심사 대기입니다. 상태별로 최초 접수 시각·ID 순서로 조회합니다.")
    public ResponseEntity<ApiResponse<AdminRefundPage>> search(@RequestParam(defaultValue = "PENDING_REVIEW") RefundStatus status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(AdminRefundPage.from(read.search(status, page, size))));
    }

    @GetMapping("/{refundId}")
    @Operation(operationId = "readAdminRefund", summary = "관리자 환불 상세")
    public ResponseEntity<ApiResponse<AdminRefundResponse>> detail(@PathVariable String refundId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(AdminRefundResponse.from(read.read(refundId))));
    }

    @PostMapping("/{refundId}/decision")
    @Operation(operationId = "decideRefund", summary = "관리자 환불 최초 판정", description = "ADMIN의 sub로 판정자와 사유를 저장합니다. 먼저 저장된 판정이 확정되며 동일 명령·내용의 재전송은 최초 판정 영수증을 반환합니다. 승인은 환불 완료를 의미하지 않습니다. 반려 사유는 고객에게 표시됩니다.")
    public ResponseEntity<ApiResponse<AdminRefundDecisionResponse>> decide(@CurrentSubject String operator,
            @PathVariable String refundId, @RequestBody RefundReviewRequestBody body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(
                AdminRefundDecisionResponse.from(decide.execute(body.toInput(refundId, operator)))));
    }
}
