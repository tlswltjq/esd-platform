package com.stove.payment.api.controller;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import com.stove.payment.api.controller.dto.ApprovalReviewPage;
import com.stove.payment.api.controller.dto.ApprovalReviewResponse;
import com.stove.payment.api.controller.dto.ApprovalInvestigationBody;
import com.stove.payment.api.controller.dto.ApprovalInvestigationPage;
import com.stove.payment.api.controller.dto.ApprovalInvestigationResponse;
import com.stove.payment.core.domain.ApprovalReviewLookup;
import com.stove.payment.core.domain.ApprovalReviewQuery;
import com.stove.payment.core.service.ReadApprovalReviews;
import com.stove.payment.core.service.RecordApprovalInvestigation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.net.URI;
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
@RequestMapping("/api/v1/admin/approval-reviews")
public class AdminApprovalReviewController {
    private final ReadApprovalReviews read;
    private final RecordApprovalInvestigation record;

    @GetMapping
    @Operation(operationId = "searchApprovalReviews", summary = "관리자 승인 보류 목록", description = "저장된 승인 보류 근거와 조사 기록. 금융 상태와 재시도 일정을 변경하지 않습니다.")
    public ResponseEntity<ApiResponse<ApprovalReviewPage>> search(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String reason) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(ApprovalReviewPage.from(read.search(new ApprovalReviewQuery(page, size, reason)))));
    }

    @GetMapping("/{approvalId}")
    @Operation(operationId = "readApprovalReview", summary = "관리자 승인 보류 상세", description = "저장된 승인 보류 근거와 조사 기록. 금융 상태와 재시도 일정을 변경하지 않습니다.")
    public ResponseEntity<ApiResponse<ApprovalReviewResponse>> detail(@PathVariable String approvalId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(ApprovalReviewResponse.from(read.read(new ApprovalReviewLookup(approvalId)))));
    }

    @GetMapping("/{approvalId}/investigations")
    @Operation(operationId = "readApprovalInvestigations", summary = "관리자 승인 보류 조사 이력", description = "저장된 승인 보류 근거와 조사 기록. 금융 상태와 재시도 일정을 변경하지 않습니다.")
    public ResponseEntity<ApiResponse<ApprovalInvestigationPage>> history(@PathVariable String approvalId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(ApprovalInvestigationPage.from(read.history(new ApprovalReviewLookup(approvalId), new ApprovalReviewQuery(page, size, null)))));
    }

    @PostMapping("/{approvalId}/investigations")
    @Operation(operationId = "recordApprovalInvestigation", summary = "관리자 승인 보류 조사 기록 추가", description = "저장된 승인 보류 근거와 조사 기록. 금융 상태와 재시도 일정을 변경하지 않습니다.")
    public ResponseEntity<ApiResponse<ApprovalInvestigationResponse>> record(@CurrentSubject String operator,
            @PathVariable String approvalId, @RequestBody ApprovalInvestigationBody body) {
        ApprovalInvestigationResponse receipt = ApprovalInvestigationResponse.from(record.execute(body.toInput(approvalId, operator)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).location(URI.create("/api/v1/admin/approval-reviews/" + receipt.approvalId() + "/investigations"))
                .body(ApiResponse.ok(receipt));
    }
}
