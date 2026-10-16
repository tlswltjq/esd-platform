package com.stove.studio.core.domain.review;

import com.stove.common.web.error.*;

public record ProjectReviewResult(String eventId, String eventType, Long reviewRequestId,
                                  Long projectId, long contentVersion, boolean approved,
                                  String feedback, String actor) {
    public ProjectReviewResult {
        if (!text(eventId, 64) || !text(eventType, 60)
                || reviewRequestId == null || reviewRequestId <= 0
                || projectId == null || projectId <= 0 || contentVersion <= 0
                || !text(actor, 100) || (approved && feedback != null)
                || (!approved && !text(feedback, 2000)))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "심사 결과가 올바르지 않습니다.");
    }

    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }
}
