package com.stove.webbff.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.contract.EventEnvelope;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.AccountAccessChangedEvent;
import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.webbff.core.service.EndSubjectSessions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Auth의 계정 접근 변경 사실을 받아 그 subject의 BFF 세션을 끝낸다(결정 180·225). 같은 토픽의 다른 사실(가입 등)은 무시한다.
 *
 * <p>결정 226: 봉투·역직렬화({@code IllegalStateException})·계약({@code IllegalArgumentException}) 위반은 영구 실패라 바로
 * DLT로, Redis 장애 같은 일시 실패는 성공할 때까지 재시도한다.
 */
@Component
@RequiredArgsConstructor
@EventSubscriber(topics = Topics.AUTH, group = EndSubjectSessions.CONSUMER_GROUP,
        noRetryFor = {IllegalStateException.class, IllegalArgumentException.class}, retryUntilSuccess = true)
public class AccountAccessEventListener implements EventHandler {

    private final ObjectMapper mapper;
    private final EndSubjectSessions endSubjectSessions;

    @Override
    public void receive(InboundMessage message) {
        EventEnvelope envelope = EventEnvelope.from(message);
        if (envelope.isType(EventType.ACCOUNT_ACCESS_CHANGED)) {
            endSubjectSessions.execute(AccountAccessChangedMapper.from(
                    envelope, envelope.payloadAs(mapper, AccountAccessChangedEvent.class)));
        }
    }
}
