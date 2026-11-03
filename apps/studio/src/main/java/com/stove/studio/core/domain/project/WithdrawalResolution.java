package com.stove.studio.core.domain.project;

import com.stove.common.kafka.contract.payload.ProjectWithdrawalResult;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.time.Instant;

public record WithdrawalResolution(String eventId, String eventType, Instant occurredAt,
        ProjectRequestKind kind, ProjectWithdrawalResult result) {
    public WithdrawalResolution {
        ProjectCommandValues.requireRequestKey(eventId);
        if (occurredAt == null || result == null || result.withdrawalId() == null || result.withdrawalId() <= 0
                || result.requestId() == null || result.requestId() <= 0
                || result.projectId() == null || result.projectId() <= 0 || result.contentVersion() <= 0
                || result.productCode() == null || !result.productCode().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}")
                || result.actor() == null || result.actor().isBlank() || result.actor().length() > 100
                || result.finalizedAt() == null || result.requestEventId() == null
                || result.requestEventId().isBlank() || result.requestEventId().length() > 64)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "철회 결과 근거가 올바르지 않습니다.");
        if (kind == ProjectRequestKind.WITHDRAW_REVIEW
                && "ProjectReviewWithdrawalResolved".equals(eventType)) {
            ProjectCommandValues.requireReviewResult(result.status(), result.actor(), result.feedback(), result.finalizedAt());
        } else if (kind == ProjectRequestKind.WITHDRAW_RELEASE
                && "ProjectReleaseWithdrawalResolved".equals(eventType)) {
            ProjectCommandValues.requireReleaseResult(result.status(), result.feedback());
        } else {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "철회 결과 종류가 올바르지 않습니다.");
        }
    }
}
