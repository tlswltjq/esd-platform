package com.stove.payment.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.payload.LicenseIssueFailedEvent;
import com.stove.common.event.kafka.EventEnvelope;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.payment.application.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Saga 보상 트랜잭션.</b>
 * 결제는 성공했는데 라이선스 지급에 최종 실패하면 결제를 자동 환불해
 * "돈은 빠졌는데 게임은 없는" 상태를 시스템이 스스로 해소한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LicenseEventListener {

    private static final String GROUP = "payment-service";

    private final PaymentService paymentService;
    private final ProcessedEventGuard processedEventGuard;
    private final ObjectMapper objectMapper;

    @Transactional
    @KafkaListener(topics = Topics.LICENSE, groupId = GROUP)
    public void onLicenseEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);
        if (!envelope.isType(EventType.LICENSE_ISSUE_FAILED)) {
            return;
        }
        if (!processedEventGuard.firstDelivery(envelope.eventId(), GROUP, envelope.eventType())) {
            return;
        }
        LicenseIssueFailedEvent event = envelope.payloadAs(objectMapper, LicenseIssueFailedEvent.class);
        log.warn("라이선스 지급 실패 → 보상 환불 실행 orderNo={} reason={}", event.orderNo(), event.reason());
        paymentService.cancel(event.orderNo(), "LICENSE_ISSUE_FAILED:" + event.reason());
    }
}
