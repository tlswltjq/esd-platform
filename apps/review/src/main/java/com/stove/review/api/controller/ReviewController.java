package com.stove.review.api.controller;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import com.stove.review.api.controller.dto.ProjectReviewPageResponse;
import com.stove.review.api.controller.dto.ProjectReviewResponse;
import com.stove.review.api.controller.dto.ProjectReviewSummary;
import com.stove.review.api.controller.dto.RejectProjectReviewRequest;
import com.stove.review.core.domain.BuildDownload;
import com.stove.review.core.domain.ProjectReview;
import com.stove.review.core.domain.ReviewStatus;
import com.stove.review.core.service.DecideProjectReview;
import com.stove.review.core.service.ReadProjectReviews;
import com.stove.review.core.service.ReadReviewBuildDownload;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
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
