package com.stove.studio.core.domain.project;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.time.Instant;

public final class ProjectCommandValues {
    private ProjectCommandValues() {}

    public static void requireRequestKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "요청 식별자가 올바르지 않습니다.");
    }

    public static void requireContentVersion(long version) {
        if (version <= 0) throw new BusinessException(ErrorCode.INVALID_REQUEST, "작업본 버전이 올바르지 않습니다.");
    }

    public static void requireReason(String reason) {
        if (reason != null && reason.length() > 2000)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "철회 사유는 2000자 이하여야 합니다.");
    }

    public static void requireReviewResult(String status, String actor, String feedback, Instant at) {
        if (!("APPROVED".equals(status) || "REJECTED".equals(status) || "WITHDRAWN".equals(status))
                || actor == null || actor.isBlank() || actor.length() > 100 || at == null
                || ("APPROVED".equals(status) && feedback != null)
                || ("REJECTED".equals(status) && (feedback == null || feedback.isBlank())))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "심사 최종 결과가 올바르지 않습니다.");
        requireReason(feedback);
    }

    public static void requireReleaseResult(String status, String feedback) {
        if (!("COMPLETED".equals(status) || "REJECTED".equals(status) || "WITHDRAWN".equals(status))
                || ("COMPLETED".equals(status) && feedback != null)
                || ("REJECTED".equals(status) && (feedback == null || feedback.isBlank())))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "출시 최종 결과가 올바르지 않습니다.");
        requireReason(feedback);
    }
}
