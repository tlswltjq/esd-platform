package com.stove.catalog.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.catalog.core.domain.*;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.*;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.*;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class PublishProjectRelease {
    public static final String CONSUMER_GROUP = "catalog-project-release";
    private final CatalogProductRepository products;
    private final CatalogReleaseRepository releases;
    private final ProcessedEventGuard guard;
    private final OutboxRecorder outbox;
    private final ObjectMapper mapper;
    private final EntityManager entityManager;

    public void execute(EventEnvelope envelope, ProjectReleaseRequestedEvent event) {
        validate(envelope, event);
        // Check an existing request even when the Inbox already knows the event ID.
        var existing = releases.findById(event.releaseRequestId()).orElse(null);
        if (existing != null) {
            if (!existing.getRequestEventId().equals(event.eventId())
                    || !existing.getReviewRequestId().equals(event.reviewRequestId())
                    || !snapshot(existing).equals(event.snapshot()))
                throw new BusinessException(ErrorCode.CONFLICT, "동일 출시 요청의 내용이 다릅니다.");
            return;
        }
        releases.findByRequestEventId(event.eventId())
                .ifPresent(
                        previous -> {
                            if (!previous.getReleaseRequestId().equals(event.releaseRequestId()))
                                throw new BusinessException(
                                        ErrorCode.CONFLICT, "이벤트 ID가 다른 출시 요청에 사용되었습니다.");
                        });
        if (!guard.firstDelivery(event.eventId(), CONSUMER_GROUP, event.eventType())) return;
        var s = event.snapshot();
        var product = products.lockByProjectId(s.projectId()).orElse(null);
        if (product != null) product.requireIdentity(s);
        else if (products.findByProductCode(s.productCode()).isPresent())
            throw new BusinessException(ErrorCode.CONFLICT, "상품 코드가 다른 Project에 연결되어 있습니다.");
        releases.findFirstByProjectIdAndContentVersionOrderByReleaseRequestIdAsc(
                        s.projectId(), s.contentVersion())
                .ifPresent(
                        previous -> {
                            if (!snapshot(previous).equals(s))
                                throw new BusinessException(
                                        ErrorCode.CONFLICT, "같은 버전의 스냅샷이 다릅니다.");
                        });
        long currentVersion =
                product == null
                        ? 0
                        : releases.findById(product.getCurrentReleaseRequestId())
                                .orElseThrow()
                                .getContentVersion();
        boolean stale = s.contentVersion() < currentVersion;
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var release = CatalogRelease.decided(event, json(s), stale, now);
        // Assigned request IDs must INSERT, never merge over a concurrently committed decision.
        entityManager.persist(release);
        entityManager.flush();
        boolean publicationChanged = product == null || s.contentVersion() > currentVersion;
        if (product == null)
            product = products.saveAndFlush(CatalogProduct.publish(s, event.releaseRequestId(), now));
        else if (s.contentVersion() > currentVersion)
            product.replace(event.releaseRequestId(), now);
        if (publicationChanged)
            outbox.record(
                    "CatalogProduct", s.productCode(),
                    CatalogProductPublishedEvent.of(new CatalogProductSnapshot(
                            product.getId(), s.productCode(), s.title(), s.shortDescription(),
                            s.thumbnailUrl(), s.price(), s.currency(), product.getPublishedAt(),
                            s.content(), s.videoUrl(), s.contentVersion(), s.version(), product.getUpdatedAt())));
        if (stale)
            outbox.record(
                    "CatalogProduct",
                    s.productCode(),
                    ProjectReleaseRejectedEvent.of(
                            release.getReleaseRequestId(),
                            s.projectId(),
                            s.contentVersion(),
                            s.productCode(),
                            release.getReason(),
                            release.getRequestEventId()));
        else
            outbox.record(
                    "CatalogProduct",
                    s.productCode(),
                    ProjectReleaseCompletedEvent.of(
                            release.getReleaseRequestId(),
                            s.projectId(),
                            s.contentVersion(),
                            s.productCode(),
                            release.getRequestEventId()));
    }

    private ProjectSnapshot snapshot(CatalogRelease release) {
        try {
            return mapper.readValue(release.getSnapshot(), ProjectSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 공개 스냅샷을 읽을 수 없습니다.", e);
        }
    }

    private String json(ProjectSnapshot snapshot) {
        try {
            return mapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("공개 스냅샷 직렬화 실패", e);
        }
    }

    private static void validate(EventEnvelope envelope, ProjectReleaseRequestedEvent event) {
        var s = event == null ? null : event.snapshot();
        if (envelope == null
                || event == null
                || s == null
                || !text(envelope.eventId(), 64)
                || !Objects.equals(envelope.eventId(), event.eventId())
                || !Objects.equals(envelope.eventType(), event.eventType())
                || !Objects.equals(envelope.key(), s.productCode())
                || event.occurredAt() == null
                || event.releaseRequestId() == null
                || event.releaseRequestId() <= 0
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
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "출시 요청 이벤트 계약이 올바르지 않습니다.");
    }

    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }
}
