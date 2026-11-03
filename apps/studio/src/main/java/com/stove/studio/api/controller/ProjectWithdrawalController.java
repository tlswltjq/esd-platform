package com.stove.studio.api.controller;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import com.stove.studio.api.controller.dto.WithdrawProjectRequest;
import com.stove.studio.core.domain.project.ProjectRequestCommand;
import com.stove.studio.core.domain.project.ProjectRequestKind;
import com.stove.studio.core.service.project.RequestProjectWithdrawal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/studio/projects/{id}")
public class ProjectWithdrawalController {
    private final RequestProjectWithdrawal withdraw;

    @PostMapping("/review-requests/{targetId}/withdrawals")
    public ResponseEntity<ApiResponse<ProjectRequestCommand>> review(
            @PathVariable Long id, @PathVariable Long targetId, @CurrentSubject String subject,
            @Valid @RequestBody WithdrawProjectRequest request) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(withdraw.review(id, targetId, subject, request.command())));
    }

    @PostMapping("/release-requests/{targetId}/withdrawals")
    public ResponseEntity<ApiResponse<ProjectRequestCommand>> release(
            @PathVariable Long id, @PathVariable Long targetId, @CurrentSubject String subject,
            @Valid @RequestBody WithdrawProjectRequest request) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(withdraw.release(id, targetId, subject, request.command())));
    }

    @GetMapping("/review-requests/{targetId}/withdrawals/{withdrawalId}")
    public ResponseEntity<ApiResponse<ProjectRequestCommand>> reviewReceipt(
            @PathVariable Long id, @PathVariable Long targetId, @PathVariable Long withdrawalId,
            @CurrentSubject String subject) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(
                withdraw.receipt(id, targetId, withdrawalId, subject, ProjectRequestKind.WITHDRAW_REVIEW)));
    }

    @GetMapping("/review-requests/{targetId}/withdrawals")
    public ResponseEntity<ApiResponse<List<ProjectRequestCommand>>> reviewReceipts(
            @PathVariable Long id, @PathVariable Long targetId, @CurrentSubject String subject,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(
                withdraw.receipts(id, targetId, subject, ProjectRequestKind.WITHDRAW_REVIEW, page, size)));
    }

    @GetMapping("/release-requests/{targetId}/withdrawals")
    public ResponseEntity<ApiResponse<List<ProjectRequestCommand>>> releaseReceipts(
            @PathVariable Long id, @PathVariable Long targetId, @CurrentSubject String subject,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(
                withdraw.receipts(id, targetId, subject, ProjectRequestKind.WITHDRAW_RELEASE, page, size)));
    }

    @GetMapping("/release-requests/{targetId}/withdrawals/{withdrawalId}")
    public ResponseEntity<ApiResponse<ProjectRequestCommand>> releaseReceipt(
            @PathVariable Long id, @PathVariable Long targetId, @PathVariable Long withdrawalId,
            @CurrentSubject String subject) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(
                withdraw.receipt(id, targetId, withdrawalId, subject, ProjectRequestKind.WITHDRAW_RELEASE)));
    }
}
