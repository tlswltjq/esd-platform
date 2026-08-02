package com.stove.download.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.kafka.EventEnvelope;
import com.stove.common.event.payload.BuildUploadedEvent;
import com.stove.common.event.payload.LicenseIssuedEvent;
import com.stove.common.event.payload.LicenseRevokedEvent;
import com.stove.common.event.payload.ProductChangedEvent;
import com.stove.download.core.service.DownloadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * download 는 세 방향의 이벤트를 받는다.
 * <ul>
 *   <li>studio.BuildUploaded → 패치 매니페스트</li>
 *   <li>catalog.ProductChanged → productCode ↔ productId 참조</li>
 *   <li>license.LicenseIssued/Revoked → 다운로드 권한</li>
 * </ul>
 * 모두 문서 ID 고정 upsert 라 Inbox 테이블 없이 멱등하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DownloadEventListener {

    private static final String GROUP = "download";

    private final DownloadService downloadService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = Topics.STUDIO, groupId = GROUP)
    public void onStudioEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);
        if (envelope.isType(EventType.BUILD_UPLOADED)) {
            downloadService.registerManifest(envelope.payloadAs(objectMapper, BuildUploadedEvent.class));
        }
    }

    @KafkaListener(topics = Topics.CATALOG, groupId = GROUP)
    public void onCatalogEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);
        if (envelope.isType(EventType.PRODUCT_CHANGED)) {
            downloadService.upsertProductRef(envelope.payloadAs(objectMapper, ProductChangedEvent.class));
        }
    }

    @KafkaListener(topics = Topics.LICENSE, groupId = GROUP)
    public void onLicenseEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);

        if (envelope.isType(EventType.LICENSE_ISSUED)) {
            LicenseIssuedEvent event = envelope.payloadAs(objectMapper, LicenseIssuedEvent.class);
            downloadService.grant(event.orderNo(), event.memberId(), event.productIds());

        } else if (envelope.isType(EventType.LICENSE_REVOKED)) {
            LicenseRevokedEvent event = envelope.payloadAs(objectMapper, LicenseRevokedEvent.class);
            downloadService.revoke(event.orderNo(), event.memberId(), event.productIds());
        }
    }
}
