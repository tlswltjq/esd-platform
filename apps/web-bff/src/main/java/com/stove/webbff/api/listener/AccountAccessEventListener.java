package com.stove.webbff.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.contract.EventEnvelope;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.AccountAccessChangedEvent;
import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.webbff.core.domain.AccessChangeRejected;
import com.stove.webbff.core.domain.SubjectAccessChange;
import com.stove.webbff.core.service.EndSubjectSessions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Auth의 계정 접근 변경 사실을 받아 그 subject의 BFF 세션을 끝낸다(결정 180·225). 같은 토픽의 다른 사실(가입 등)은 무시한다.
 *
 * <p>결정 226: 봉투·역직렬화·계약 위반만 {@link AccessChangeRejected}(영구 실패)로 바꿔 바로 DLT로 보낸다. 처리 단계의 예외는
 * 종류와 상관없이 일시 실패라 성공할 때까지 재시도한다.
 */
@Component
@RequiredArgsConstructor
@EventSubscriber(topics = Topics.AUTH, group = EndSubjectSessions.CONSUMER_GROUP,
        noRetryFor = AccessChangeRejected.class, retryUntilSuccess = true)
public class AccountAccessEventListener implements EventHandler {

    private final ObjectMapper mapper;
    private final EndSubjectSessions endSubjectSessions;

    @Override
    public void receive(InboundMessage message) {
        EventEnvelope envelope = envelope(message);
        if (envelope.isType(EventType.ACCOUNT_ACCESS_CHANGED)) {
            endSubjectSessions.execute(change(envelope));
        }
    }

    private static EventEnvelope envelope(InboundMessage message) {
        try {
            return EventEnvelope.from(message);
        } catch (IllegalStateException | IllegalArgumentException invalid) {
            throw new AccessChangeRejected("봉투가 올바르지 않다: " + message.origin(), invalid);
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
