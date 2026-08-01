package com.stove.review.api.dto;

import com.stove.review.domain.ReviewRequest;
import com.stove.review.domain.ReviewStatus;

public record ReviewResponse(
        Long reviewId,
        Long gameId,
        String productCode,
        String title,
        Long sellerId,
        boolean selfRated,
        ReviewStatus status,
        String boardTicketId,
        String ratingCode,
        String rejectReasonCode,
        String rejectReason
) {
    public static ReviewResponse from(ReviewRequest request) {
        return new ReviewResponse(
                request.getId(),
                request.getGameId(),
                request.getProductCode(),
                request.getTitle(),
                request.getSellerId(),
                request.isSelfRated(),
                request.getStatus(),
                request.getBoardTicketId(),
                request.getRatingCode(),
                request.getRejectReasonCode(),
                request.getRejectReason());
    }
}
