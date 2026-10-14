package com.stove.review.api.controller;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import com.stove.review.api.controller.dto.*;
import com.stove.review.core.domain.*;
import com.stove.review.core.service.*;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/reviews")
@SecurityRequirement(name = "oauth2", scopes = "studio")
public class ReviewController {
    private final ReadProjectReviews read;
    private final ReadReviewBuildDownload download;
    private final DecideProjectReview decide;

    @GetMapping
    public ApiResponse<ProjectReviewPageResponse> list(
            @RequestParam(required = false) ReviewStatus status,
            @RequestParam(required = false) Long projectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(
                ProjectReviewPageResponse.from(
                        read.list(status, projectId, page, size)
                                .map(r -> ProjectReviewSummary.from(r, read.snapshot(r)))));
    }

    @GetMapping("/{reviewRequestId}")
    public ApiResponse<ProjectReviewResponse> get(@PathVariable Long reviewRequestId) {
        return ApiResponse.ok(response(read.get(reviewRequestId)));
    }

    @GetMapping("/{reviewRequestId}/build-download")
    public ResponseEntity<ApiResponse<BuildDownload>> download(@PathVariable Long reviewRequestId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(download.execute(reviewRequestId)));
    }

    @PostMapping("/{reviewRequestId}/approve")
    public ApiResponse<ProjectReviewResponse> approve(
            @PathVariable Long reviewRequestId, @CurrentSubject String subject) {
        return ApiResponse.ok(
                response(decide.execute(reviewRequestId, subject, ReviewStatus.APPROVED, null)));
    }

    @PostMapping("/{reviewRequestId}/reject")
    public ApiResponse<ProjectReviewResponse> reject(
            @PathVariable Long reviewRequestId,
            @CurrentSubject String subject,
            @Valid @RequestBody RejectProjectReviewRequest request) {
        return ApiResponse.ok(
                response(
                        decide.execute(
                                reviewRequestId,
                                subject,
                                ReviewStatus.REJECTED,
                                request.feedback())));
    }

    private ProjectReviewResponse response(ProjectReview review) {
        return ProjectReviewResponse.from(review, read.snapshot(review));
    }
}
