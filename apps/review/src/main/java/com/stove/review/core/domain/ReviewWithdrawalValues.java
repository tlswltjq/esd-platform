package com.stove.review.core.domain;

import com.stove.common.kafka.contract.payload.ProjectWithdrawalRequest;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.time.Instant;

public final class ReviewWithdrawalValues {
    private ReviewWithdrawalValues() {}

    public static void requireInstruction(ProjectWithdrawalRequest request) {
        if (request == null || request.withdrawalId() == null || request.withdrawalId() <= 0
                || request.requestId() == null || request.requestId() <= 0
                || request.projectId() == null || request.projectId() <= 0
                || request.contentVersion() <= 0 || request.productCode() == null
                || !request.productCode().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}")
                || request.ownerSubject() == null || request.ownerSubject().isBlank()
                || request.ownerSubject().length() > 100)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "심사 철회 대상이 올바르지 않습니다.");
        requireReason(request.reason());
    }

    public static void requireReason(String reason) {
        if (reason != null && (reason.isBlank() || reason.length() > 2000))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "철회 사유가 올바르지 않습니다.");
    }

    public static void requireEvent(String eventId, String eventType, Instant occurredAt) {
        if (eventId == null || eventId.isBlank() || eventId.length() > 64
                || !"ProjectReviewWithdrawalRequested".equals(eventType) || occurredAt == null)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "심사 철회 접수 근거가 올바르지 않습니다.");
    }
}
