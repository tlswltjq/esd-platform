package com.stove.review.core.domain;

import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.common.web.error.*;
import java.time.Instant;

/** Review's input facts, independent of the event that delivered them. */
public record ReviewSubmission(String eventId, String eventType, Instant occurredAt,
                               Long reviewRequestId, ProjectSnapshot snapshot) {
    public ReviewSubmission {
        ProjectSnapshot s = snapshot;
        if (!text(eventId, 64) || !text(eventType, 60) || occurredAt == null
                || reviewRequestId == null || reviewRequestId <= 0 || s == null
                || s.projectId() == null || s.projectId() <= 0 || s.contentVersion() <= 0
                || !text(s.productCode(), 50) || !s.productCode().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}")
                || !text(s.ownerSubject(), 100) || !text(s.title(), 200)
                || !text(s.shortDescription(), 500) || !text(s.content(), 100000)
                || !text(s.thumbnailUrl(), 1000) || (s.videoUrl() != null && s.videoUrl().length() > 1000)
                || s.price() < 0 || !"KRW".equals(s.currency())
                || s.buildId() == null || s.buildId() <= 0 || !text(s.version(), 100)
                || s.fileSize() <= 0 || s.checksum() == null || !s.checksum().matches("[a-fA-F0-9]{64}")
                || !text(s.storagePath(), 2000) || !s.storagePath().startsWith("s3://"))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "심사 접수 정보가 올바르지 않습니다.");
    }

    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }
}
