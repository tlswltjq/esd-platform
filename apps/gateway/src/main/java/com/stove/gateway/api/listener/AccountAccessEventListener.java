package com.stove.gateway.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.contract.EventEnvelope;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.AccountAccessChangedEvent;
import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.gateway.core.service.RecordAccessChange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Auth의 계정 접근 변경 사실을 받아 거절 기준을 남긴다(결정 180·225). 같은 토픽의 다른 사실(가입 등)은 무시한다.
 *
 * <p>결정 226: 봉투·역직렬화({@code IllegalStateException})·계약({@code IllegalArgumentException}) 위반은 영구 실패라 바로
 * DLT로, Redis 장애 같은 일시 실패는 성공할 때까지 재시도한다. 차단이 빠지면 안 되고, 멱등·순서 무관이라 다시 처리해도 같다.
 */
@Component
@RequiredArgsConstructor
@EventSubscriber(topics = Topics.AUTH, group = RecordAccessChange.CONSUMER_GROUP,
        noRetryFor = {IllegalStateException.class, IllegalArgumentException.class}, retryUntilSuccess = true)
public class AccountAccessEventListener implements EventHandler {

    private final ObjectMapper mapper;
    private final RecordAccessChange recordAccessChange;

    @Override
    public void receive(InboundMessage message) {
        EventEnvelope envelope = EventEnvelope.from(message);
        if (envelope.isType(EventType.ACCOUNT_ACCESS_CHANGED)) {
            recordAccessChange.execute(AccountAccessChangedMapper.from(
                    envelope, envelope.payloadAs(mapper, AccountAccessChangedEvent.class)));
        }
    }
}
