package com.stove.review.api.controller.dto;

import com.stove.review.core.domain.ReviewDecisionHistory;
import java.time.Instant;

public record ReviewHistoryResponse(Long historyId, String action, String actor, String fromStatus,
                                    String toStatus, String details, Instant createdAt) {
    public static ReviewHistoryResponse from(ReviewDecisionHistory value) {
        return new ReviewHistoryResponse(value.getId(), value.getAction(), value.getActor(),
                value.getFromStatus(), value.getToStatus(), value.getDetails(), value.getCreatedAt());
    }
}
