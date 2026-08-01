package com.stove.review.api.controller;

import com.stove.common.core.response.ApiResponse;
import com.stove.review.api.controller.dto.RejectRequest;
import com.stove.review.api.controller.dto.ReviewResponse;
import com.stove.review.core.domain.ReviewStatus;
import com.stove.review.core.service.ReviewService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 심의 담당자용 운영 API. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    @GetMapping
    public ApiResponse<List<ReviewResponse>> list(@RequestParam(required = false) ReviewStatus status) {
        return ApiResponse.ok(reviewService.getRequests(status).stream()
                .map(ReviewResponse::from)
                .toList());
    }

    @PostMapping("/{reviewId}/approve")
    public ApiResponse<Void> approve(@PathVariable Long reviewId,
                                     @RequestParam(defaultValue = "ALL") String ratingCode) {
        reviewService.approve(reviewId, ratingCode);
        return ApiResponse.ok();
    }

    @PostMapping("/{reviewId}/reject")
    public ApiResponse<Void> reject(@PathVariable Long reviewId, @Valid @RequestBody RejectRequest request) {
        reviewService.reject(reviewId, request.reasonCode(), request.reason());
        return ApiResponse.ok();
    }
}
