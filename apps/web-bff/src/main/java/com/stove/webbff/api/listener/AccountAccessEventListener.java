package com.stove.webbff.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.contract.EventEnvelope;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.AccountAccessChangedEvent;
import com.stove.webbff.core.domain.AccessChangeRejected;
import com.stove.webbff.core.domain.SubjectAccessChange;
import com.stove.webbff.core.service.EndSubjectSessions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import com.stove.webbff.config.AccessChangeKafkaConfiguration;

/**
 * Auth의 계정 접근 변경 사실을 받아 그 subject의 BFF 세션을 끝낸다(결정 180·225). 같은 토픽의 다른 사실(가입 등)은 무시한다.
 *
 * <p>결정 226: 봉투·역직렬화·계약 위반만 {@link AccessChangeRejected}(영구 실패)로 바꿔 바로 DLT로 보낸다. 처리 단계의 예외는
 * 종류와 상관없이 일시 실패라 성공할 때까지 재시도한다.
 */
@Component
@RequiredArgsConstructor
public class AccountAccessEventListener {

    private final ObjectMapper mapper;
    private final EndSubjectSessions endSubjectSessions;

    @KafkaListener(topics = Topics.AUTH, groupId = EndSubjectSessions.CONSUMER_GROUP,
            containerFactory = AccessChangeKafkaConfiguration.ACCESS_CHANGE_FACTORY)
    public void receive(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = envelope(record);
        if (envelope.isType(EventType.ACCOUNT_ACCESS_CHANGED)) {
            endSubjectSessions.execute(change(envelope));
        }
    }

    private static EventEnvelope envelope(ConsumerRecord<String, String> record) {
        try {
            return EventEnvelope.from(record);
        } catch (IllegalStateException | IllegalArgumentException invalid) {
            throw new AccessChangeRejected(
                    "봉투가 올바르지 않다: partition=%d offset=%d".formatted(record.partition(), record.offset()), invalid);
        }
    }

    private SubjectAccessChange change(EventEnvelope envelope) {
        try {
            return AccountAccessChangedMapper.from(envelope, envelope.payloadAs(mapper, AccountAccessChangedEvent.class));
        } catch (IllegalStateException | IllegalArgumentException invalid) {
            throw new AccessChangeRejected("계정 접근 변경 사실이 계약을 어긴다: " + envelope.eventId(), invalid);
        }
    }
}
