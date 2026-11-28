package com.stove.gateway.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.contract.EventMessage;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.AccountAccessChangedEvent;
import com.stove.gateway.core.domain.AccessChange;
import com.stove.gateway.core.domain.AccessChangeRejected;
import com.stove.gateway.core.service.RecordAccessChange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import com.stove.gateway.config.AccessChangeKafkaConfiguration;

/**
 * Auth의 계정 접근 변경 사실을 받아 거절 기준을 남긴다(결정 180·225). 같은 토픽의 다른 사실(가입 등)은 무시한다.
 *
 * <p>결정 226: 헤더·역직렬화·계약 위반만 {@link AccessChangeRejected}(영구 실패)로 바꿔 바로 DLT로 보낸다. 처리 단계의 예외는
 * 종류와 상관없이 일시 실패라 성공할 때까지 재시도한다. 차단이 빠지면 안 되고, 멱등·순서 무관이라 다시 처리해도 같다.
 */
@Component
@RequiredArgsConstructor
public class AccountAccessEventListener {

    private final ObjectMapper mapper;
    private final RecordAccessChange recordAccessChange;

    @KafkaListener(topics = Topics.AUTH, groupId = RecordAccessChange.CONSUMER_GROUP,
            containerFactory = AccessChangeKafkaConfiguration.ACCESS_CHANGE_FACTORY)
    public void receive(ConsumerRecord<String, String> record) {
        EventMessage message = message(record);
        if (message.isType(EventType.ACCOUNT_ACCESS_CHANGED)) {
            recordAccessChange.execute(change(message));
        }
    }

    private static EventMessage message(ConsumerRecord<String, String> record) {
        try {
            return EventMessage.from(record);
        } catch (IllegalStateException | IllegalArgumentException invalid) {
            throw new AccessChangeRejected(
                    "이벤트 메시지가 올바르지 않다: partition=%d offset=%d".formatted(record.partition(), record.offset()), invalid);
        }
    }

    private AccessChange change(EventMessage message) {
        try {
            return AccountAccessChangedMapper.from(message, message.payloadAs(mapper, AccountAccessChangedEvent.class));
        } catch (IllegalStateException | IllegalArgumentException invalid) {
            throw new AccessChangeRejected("계정 접근 변경 사실이 계약을 어긴다: " + message.eventId(), invalid);
        }
    }
}
