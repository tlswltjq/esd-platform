package com.stove.payment.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.payload.LicenseIssueFailedEvent;
import com.stove.common.event.kafka.EventEnvelope;
import com.stove.payment.core.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * <b>Saga 보상 트랜잭션.</b>
 * 결제는 성공했는데 라이선스 지급에 최종 실패하면 결제를 자동 환불해
 * "돈은 빠졌는데 게임은 없는" 상태를 시스템이 스스로 해소한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LicenseEventListener {

    private static final String GROUP = "payment";

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = Topics.LICENSE, groupId = GROUP)
    public void onLicenseEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);
        if (!envelope.isType(EventType.LICENSE_ISSUE_FAILED)) {
            return;
        }
        LicenseIssueFailedEvent event = envelope.payloadAs(objectMapper, LicenseIssueFailedEvent.class);
        paymentService.compensate(envelope.eventId(), envelope.eventType(), event.orderNo(),
                "LICENSE_ISSUE_FAILED:" + event.reason());
    }
}
