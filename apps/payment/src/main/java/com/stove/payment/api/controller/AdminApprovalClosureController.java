package com.stove.payment.api.controller;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import com.stove.payment.api.controller.dto.ApprovalClosureBody;
import com.stove.payment.api.controller.dto.ApprovalClosureResponse;
import com.stove.payment.core.service.CloseApprovalReview;
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

/** Operator closure of a held PortOne approval (decision 115). Only PortOne approvals can be held for review. */
@RestController
@ConditionalOnProperty(name = "stove.payment.pg.provider", havingValue = "PORTONE_TEST")
@SecurityRequirement(name = "oauth2", scopes = "commerce")
@RequestMapping("/api/v1/admin/approval-reviews")
public class AdminApprovalClosureController {
    private final CloseApprovalReview close;

    public AdminApprovalClosureController(CloseApprovalReview close) { this.close = close; }

    @PostMapping("/{approvalId}/closure")
    @Operation(operationId = "closeApprovalReview", summary = "관리자 승인 보류 종결",
            description = "PG를 다시 조회해 전액 취소·실패·READY 근거가 확인될 때만 보류를 끝내고 주문을 실패(또는 이미 만료)로 마칩니다. "
                    + "PAID·진행 중·근거 불일치·조회 장애는 409입니다. PG 취소·승인을 실행하지 않습니다.")
    public ResponseEntity<ApiResponse<ApprovalClosureResponse>> close(@CurrentSubject String operator,
            @PathVariable String approvalId, @RequestBody ApprovalClosureBody body) {
        ApprovalClosureResponse receipt = ApprovalClosureResponse.from(close.execute(body.toInput(approvalId, operator)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).location(URI.create("/api/v1/admin/approval-reviews/" + receipt.approvalId()))
                .body(ApiResponse.ok(receipt));
    }
}
