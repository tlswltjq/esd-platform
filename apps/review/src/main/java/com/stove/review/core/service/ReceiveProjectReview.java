package com.stove.review.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.*;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.web.error.*;
import com.stove.review.core.domain.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional
public class ReceiveProjectReview {
    public static final String CONSUMER_GROUP = "review-project";
    private final ProjectReviewRepository reviews;
    private final ProcessedEventGuard guard;
    private final ObjectMapper mapper;

    public void execute(EventEnvelope envelope, ProjectReviewRequestedEvent event) {
        validate(envelope, event);
        if (!guard.firstDelivery(envelope.eventId(), CONSUMER_GROUP, envelope.eventType())) return;
        var existing = reviews.findById(event.reviewRequestId()).orElse(null);
        if (existing != null) {
            try {
                if (!event.snapshot()
                        .equals(mapper.readValue(existing.getSnapshot(), ProjectSnapshot.class)))
                    throw new BusinessException(ErrorCode.CONFLICT, "동일 요청의 스냅샷이 다릅니다.");
            } catch (JsonProcessingException failure) {
                throw new IllegalStateException("저장된 심사 스냅샷을 읽을 수 없습니다.", failure);
            }
            return;
        }
        try {
            reviews.saveAndFlush(
                    ProjectReview.requested(event, mapper.writeValueAsString(event.snapshot())));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("심사 스냅샷 직렬화 실패", failure);
        }
    }

    private static void validate(EventEnvelope envelope, ProjectReviewRequestedEvent event) {
        var s = event == null ? null : event.snapshot();
        if (envelope == null
                || event == null
                || s == null
                || !text(envelope.eventId(), 64)
                || !Objects.equals(envelope.eventId(), event.eventId())
                || !Objects.equals(envelope.eventType(), event.eventType())
                || !Objects.equals(envelope.key(), s.productCode())
                || event.occurredAt() == null
                || event.reviewRequestId() == null
                || event.reviewRequestId() <= 0
                || s.projectId() == null
                || s.projectId() <= 0
                || s.contentVersion() <= 0
                || !text(s.productCode(), 50)
                || !s.productCode().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}")
                || !text(s.ownerSubject(), 100)
                || !text(s.title(), 200)
                || !text(s.shortDescription(), 500)
                || !text(s.content(), 100000)
                || !text(s.thumbnailUrl(), 1000)
                || (s.videoUrl() != null && s.videoUrl().length() > 1000)
                || s.price() < 0
                || !"KRW".equals(s.currency())
                || s.buildId() == null
                || s.buildId() <= 0
                || !text(s.version(), 100)
                || s.fileSize() <= 0
                || s.checksum() == null
                || !s.checksum().matches("[a-fA-F0-9]{64}")
                || !text(s.storagePath(), 2000)
                || !s.storagePath().startsWith("s3://"))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "심사 요청 이벤트 계약이 올바르지 않습니다.");
    }

    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }
}
