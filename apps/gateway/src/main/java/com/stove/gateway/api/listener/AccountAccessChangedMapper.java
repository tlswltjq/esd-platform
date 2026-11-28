package com.stove.gateway.api.listener;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.AccountAccessChangedEvent;
import com.stove.gateway.core.domain.AccessChange;
import java.util.Objects;

/** 헤더·본문·키를 검증해 내부 입력으로 바꾼다. 어기면 영구 실패({@link IllegalArgumentException})라 재시도 없이 DLT로 간다(결정 226). */
public final class AccountAccessChangedMapper {

    private AccountAccessChangedMapper() {
    }

    public static AccessChange from(EventMessage message, AccountAccessChangedEvent event) {
        if (message == null || event == null
                || !Objects.equals(message.eventId(), event.eventId())
                || !Objects.equals(message.eventType(), event.eventType())
                || !Objects.equals(message.key(), event.subject())) {
            throw new IllegalArgumentException("계정 접근 변경 사실의 헤더와 본문이 어긋납니다.");
        }
        return new AccessChange(event.eventId(), event.subject(), event.changedAt());
    }
}
