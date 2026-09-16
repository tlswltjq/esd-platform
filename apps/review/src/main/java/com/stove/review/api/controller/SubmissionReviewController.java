package com.stove.review.api.controller;

import com.stove.common.core.response.ApiResponse;
import com.stove.review.api.controller.dto.ApproveCaseRequest;
import com.stove.review.api.controller.dto.RequestChangesRequest;
import com.stove.review.api.controller.dto.ReviewCaseResponse;
import com.stove.review.core.domain.ReviewCase;
import com.stove.review.core.service.SubmissionReviewService;
import jakarta.validation.Valid;
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

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/reviews/cases")
public class SubmissionReviewController {

    private final SubmissionReviewService reviewService;

    @GetMapping
    public ApiResponse<List<ReviewCaseResponse>> cases(@RequestParam(required = false) Long submissionId) {
        return ApiResponse.ok(reviewService.cases(submissionId).stream().map(ReviewCaseResponse::from).toList());
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

    @PostMapping("/{caseId}/changes-requested")
    public ApiResponse<Void> changes(@PathVariable Long caseId, @AuthenticationPrincipal Jwt jwt,
                                     @Valid @RequestBody RequestChangesRequest request) {
        reviewService.requestChanges(caseId, jwt.getSubject(), request.reasonCode(), request.feedback());
        return ApiResponse.ok();
    }
}
