package com.stove.review.api.controller;

import com.stove.common.core.response.ApiResponse;
import com.stove.review.api.controller.dto.ApproveCaseRequest;
import com.stove.review.api.controller.dto.RequestChangesRequest;
import com.stove.review.api.controller.dto.ReviewCaseResponse;
import com.stove.review.api.controller.dto.SubmitExternalRatingRequest;
import com.stove.review.api.controller.dto.AssignReviewRequest;
import com.stove.review.api.controller.dto.UpdateChecklistRequest;
import com.stove.review.api.controller.dto.ReviewTransitionRequest;
import com.stove.review.api.controller.dto.AppealReviewRequest;
import com.stove.review.api.controller.dto.ReviewHistoryResponse;
import com.stove.review.api.controller.dto.ReviewCasePageResponse;
import com.stove.review.core.domain.ReviewCase;
import com.stove.review.core.domain.ReviewCaseStatus;
import com.stove.review.core.domain.ReviewType;
import com.stove.review.core.service.SubmissionReviewService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/reviews/cases")
@SecurityRequirement(name = "oauth2", scopes = "studio")
public class SubmissionReviewController {

    private final SubmissionReviewService reviewService;

    @GetMapping
    public ApiResponse<List<ReviewCaseResponse>> cases(@RequestParam(required = false) Long submissionId) {
        return ApiResponse.ok(reviewService.cases(submissionId).stream().map(ReviewCaseResponse::from).toList());
    }

    @GetMapping("/search")
    public ApiResponse<ReviewCasePageResponse> search(
            @RequestParam(required = false) Long submissionId,
            @RequestParam(required = false) ReviewType reviewType,
            @RequestParam(required = false) ReviewCaseStatus status,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) Boolean overdue,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.max(1, Math.min(size, 100));
        return ApiResponse.ok(ReviewCasePageResponse.from(reviewService.search(submissionId, reviewType,
                status, assignee, overdue, PageRequest.of(Math.max(page, 0), safeSize,
                        Sort.by(Sort.Direction.ASC, "dueAt", "id")))));
    }

    @PostMapping("/{caseId}/approve")
    public ApiResponse<Void> approve(@PathVariable Long caseId, @AuthenticationPrincipal Jwt jwt,
                                     @Valid @RequestBody ApproveCaseRequest request) {
        ReviewCase.RatingDecision rating = request.ratingCode() == null ? null
                : new ReviewCase.RatingDecision(request.ratingCode(), request.certificationNumber(),
                request.issuer(), request.issuedAt(), request.country());
        reviewService.approve(caseId, jwt.getSubject(), rating);
        return ApiResponse.ok();
    }

    @PostMapping("/{caseId}/external-submission")
    public ApiResponse<Void> externalSubmission(
            @PathVariable Long caseId, @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SubmitExternalRatingRequest request) {
        reviewService.submitExternal(caseId, jwt.getSubject(), request.applicationNumber(),
                request.submittedAt(), request.evidenceUrl());
        return ApiResponse.ok();
    }

    @PostMapping("/{caseId}/changes-requested")
    public ApiResponse<Void> changes(@PathVariable Long caseId, @AuthenticationPrincipal Jwt jwt,
                                     @Valid @RequestBody RequestChangesRequest request) {
        reviewService.requestChanges(caseId, jwt.getSubject(), request.reasonCode(), request.feedback(),
                request.evidenceUrl());
        return ApiResponse.ok();
    }

    @PostMapping("/{caseId}/assignment")
    public ApiResponse<ReviewCaseResponse> assign(
            @PathVariable Long caseId, @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AssignReviewRequest request) {
        return ApiResponse.ok(ReviewCaseResponse.from(reviewService.assign(caseId, jwt.getSubject(),
                request.assignee(), request.expectedVersion())));
    }

    @PostMapping("/{caseId}/checklist")
    public ApiResponse<ReviewCaseResponse> checklist(
            @PathVariable Long caseId, @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateChecklistRequest request) {
        return ApiResponse.ok(ReviewCaseResponse.from(reviewService.updateChecklist(caseId, jwt.getSubject(),
                request.checklist(), request.internalMemo(), request.expectedVersion())));
    }

    @PostMapping("/{caseId}/block")
    public ApiResponse<ReviewCaseResponse> block(
            @PathVariable Long caseId, @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ReviewTransitionRequest request) {
        return ApiResponse.ok(ReviewCaseResponse.from(reviewService.block(caseId, jwt.getSubject(),
                request.reasonCode(), request.internalMemo(), request.evidenceUrl(), request.expectedVersion())));
    }

    @PostMapping("/{caseId}/cancel")
    public ApiResponse<ReviewCaseResponse> cancel(
            @PathVariable Long caseId, @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ReviewTransitionRequest request) {
        return ApiResponse.ok(ReviewCaseResponse.from(reviewService.cancel(caseId, jwt.getSubject(),
                request.reasonCode(), request.internalMemo(), request.expectedVersion())));
    }

    @PostMapping("/{caseId}/appeal")
    public ApiResponse<ReviewCaseResponse> appeal(
            @PathVariable Long caseId, @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AppealReviewRequest request) {
        return ApiResponse.ok(ReviewCaseResponse.from(reviewService.appeal(caseId, jwt.getSubject(),
                request.reason(), request.expectedVersion())));
    }

    @GetMapping("/{caseId}/history")
    public ApiResponse<List<ReviewHistoryResponse>> history(@PathVariable Long caseId) {
        return ApiResponse.ok(reviewService.history(caseId).stream().map(ReviewHistoryResponse::from).toList());
    }
}
