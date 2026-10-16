package com.stove.studio.core.domain.release;

import com.stove.common.web.error.*;

public record ProjectReleaseResult(String eventId, String eventType, Long releaseRequestId,
                                   Long projectId, long contentVersion, boolean success,
                                   String reason, String requestEventId) {
    public ProjectReleaseResult {
        if (!text(eventId, 64) || !text(eventType, 60)
                || releaseRequestId == null || releaseRequestId <= 0
                || projectId == null || projectId <= 0 || contentVersion <= 0
                || !text(requestEventId, 64) || (success && reason != null)
                || (!success && !text(reason, 2000)))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "출시 결과가 올바르지 않습니다.");
    }

    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }
}
