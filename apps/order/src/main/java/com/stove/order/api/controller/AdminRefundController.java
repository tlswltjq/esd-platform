package com.stove.order.api.controller;

import com.stove.order.core.service.RequestRefundApprovalStop;
import com.stove.order.core.domain.RefundApprovalStopReceipt;
import com.stove.order.api.controller.dto.RefundApprovalStopBody;
import com.stove.order.api.controller.dto.RefundApprovalStopResponse;
import java.net.URI;
import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import com.stove.order.api.controller.dto.AdminRefundDecisionResponse;
import com.stove.order.api.controller.dto.AdminRefundPage;
import com.stove.order.api.controller.dto.AdminRefundResponse;
import com.stove.order.api.controller.dto.RefundReviewRequestBody;
import com.stove.order.api.controller.dto.AdminRefundReviewPage;
import com.stove.order.api.controller.dto.AdminRefundReviewResponse;
import com.stove.order.core.domain.RefundRoundDecisionRequest;
import com.stove.order.core.domain.RefundStatus;
import com.stove.order.core.service.DecideRefund;
import com.stove.order.core.service.ReadRefundReviews;
import com.stove.order.core.service.ReadRefundReviewRounds;
import com.stove.order.core.service.DecideRefundReconsideration;
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
    private final RequestRefundApprovalStop stop;
    private final ReadRefundReviews read;

    @PostMapping("/{refundId}/approval-stops")
    @Operation(operationId="stopRefundApproval", summary="관리자 잘못된 환불 승인 중단",
            description="지정한 현재 승인 회차의 Payment 실행 요청 저장 전까지만 중단합니다. ADMIN sub·필수 사유를 보존하고 같은 명령·대상·담당자·내용은 최초 영수증을 반환합니다. 필요한 실제 이용권 복구를 확인한 뒤 새 심사 회차로 복귀합니다. 202는 접수이며 복구 완료가 아닙니다.")
    public ResponseEntity<ApiResponse<RefundApprovalStopResponse>> stop(@CurrentSubject String operator,
            @PathVariable String refundId, @RequestBody RefundApprovalStopBody body) {
        RefundApprovalStopReceipt receipt=stop.execute(body.toInput(refundId,operator));
        return ResponseEntity.accepted().location(URI.create("/api/v1/admin/refunds/"+refundId)).cacheControl(CacheControl.noStore()).body(ApiResponse.ok(RefundApprovalStopResponse.from(receipt)));
    }

    private final DecideRefund decide;
    private final ReadRefundReviewRounds reviews;
    private final DecideRefundReconsideration reconsideration;

    @GetMapping("/{refundId}/reviews")
    @Operation(operationId = "readAdminRefundReviews", summary = "관리자 환불 심사 이력", description = "최초 판정과 모든 재심사 접수 사유·판정 감사 기록을 회차 순으로 조회합니다. page/size 기본 0/20, 최대 100입니다.")
    public ResponseEntity<ApiResponse<AdminRefundReviewPage>> reviews(@PathVariable String refundId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(AdminRefundReviewPage.from(reviews.admin(refundId, page, size))));
    }

    @PostMapping("/{refundId}/reconsiderations/{reviewId}/decision")
    @Operation(operationId = "decideRefundReconsideration", summary = "관리자 지정 후속 심사 회차 판정",
            description = "고객 재심사 또는 승인 정정으로 열린 현재 후속 회차의 최초 판정을 확정하고 ADMIN sub와 사유를 저장합니다. 동일 명령·회차·판정자·내용의 재전송은 해당 회차의 최초 영수증을 반환하며 이후 회차를 바꾸지 않습니다. 승인은 기존 실제 지급·정지 완료·PG 환불 절차로 이어집니다. 200은 판정 저장이며 환불 완료가 아닙니다.")
    public ResponseEntity<ApiResponse<AdminRefundReviewResponse>> reconsider(@CurrentSubject String operator,
            @PathVariable String refundId, @PathVariable String reviewId, @RequestBody RefundReviewRequestBody body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(AdminRefundReviewResponse.fromDecision(
                reconsideration.execute(new RefundRoundDecisionRequest(reviewId, body.toInput(refundId, operator))))));
    }

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
