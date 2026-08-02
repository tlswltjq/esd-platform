package com.stove.license.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.payload.PaymentCancelledEvent;
import com.stove.common.event.payload.PaymentCompletedEvent;
import com.stove.common.event.kafka.EventEnvelope;
import com.stove.license.core.service.LicenseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Saga 참여자.
 * 지급이 재시도 후에도 실패하면 보상 이벤트(LicenseIssueFailed)를 발행해 결제 환불을 유도한다.
 *
 * <p><b>여기서 예외를 잡지 않는다.</b> 스프링 카프카의 재시도는 예외가 리스너 밖으로 나올 때만
 * 작동한다 — 컨테이너는 리턴값을 보지 않고 정상 리턴을 처리 성공으로 간주해 오프셋을 커밋하며,
 * 커밋되고 나면 되감을 대상이 사라진다. 잡는 순간 재시도 횟수가 0이 된다.
 *
 * <p>보상 트리거는 재시도가 전부 소진된 뒤에만 불려야 하므로
 * {@link com.stove.license.config.KafkaErrorHandlerConfig} 의 recoverer 가 갖는다.
 * 상세는 {@code docs/kafka-consumer-retry.md}.
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
            licenseService.issue(envelope.eventId(), envelope.eventType(),
                    event.orderNo(), event.memberId(), event.lines());

        } else if (envelope.isType(EventType.PAYMENT_CANCELLED)) {
            PaymentCancelledEvent event = envelope.payloadAs(objectMapper, PaymentCancelledEvent.class);
            licenseService.revoke(envelope.eventId(), envelope.eventType(), event.orderNo(), event.reason());
        }
    }
}
