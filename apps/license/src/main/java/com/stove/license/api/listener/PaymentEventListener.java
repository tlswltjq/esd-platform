package com.stove.license.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.payload.PaymentCancelledEvent;
import com.stove.common.event.payload.PaymentCompletedEvent;
import com.stove.common.event.kafka.EventEnvelope;
import com.stove.license.application.LicenseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Saga 참여자.
 * 지급이 재시도 후에도 실패하면 보상 이벤트(LicenseIssueFailed)를 발행해 결제 환불을 유도한다.
 *
 * <p>이 메서드는 트랜잭션을 열지 않는다 — 성공 경로와 실패 경로가 서로 다른 트랜잭션 경계를
 * 가져야 하기 때문이다(성공: 지급+이벤트 원자적 커밋, 실패: 롤백 후 별도 트랜잭션으로 보상 이벤트).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final LicenseService licenseService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = Topics.PAYMENT, groupId = "license")
    public void onPaymentEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);

        if (envelope.isType(EventType.PAYMENT_COMPLETED)) {
            PaymentCompletedEvent event = envelope.payloadAs(objectMapper, PaymentCompletedEvent.class);
            try {
                licenseService.issue(envelope.eventId(), envelope.eventType(),
                        event.orderNo(), event.memberId(), event.lines());
            } catch (Exception e) {
                // DefaultErrorHandler 의 재시도까지 소진된 뒤 도달하는 경로로 운영한다.
                log.error("라이선스 지급 실패 orderNo={}", event.orderNo(), e);
                licenseService.recordIssueFailure(event.orderNo(), event.memberId(), e.getMessage());
            }

        } else if (envelope.isType(EventType.PAYMENT_CANCELLED)) {
            PaymentCancelledEvent event = envelope.payloadAs(objectMapper, PaymentCancelledEvent.class);
            licenseService.revoke(envelope.eventId(), envelope.eventType(), event.orderNo(), event.reason());
        }
    }
}
