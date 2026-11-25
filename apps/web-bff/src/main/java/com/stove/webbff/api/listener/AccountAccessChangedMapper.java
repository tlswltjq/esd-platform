package com.stove.webbff.api.listener;

import com.stove.common.contract.EventEnvelope;
import com.stove.common.contract.payload.AccountAccessChangedEvent;
import com.stove.webbff.core.domain.SubjectAccessChange;
import java.util.Objects;

/** 봉투·본문·키를 검증해 내부 입력으로 바꾼다. 어기면 영구 실패({@link IllegalArgumentException})라 재시도 없이 DLT로 간다(결정 226). */
public final class AccountAccessChangedMapper {

    private AccountAccessChangedMapper() {
    }

    public static SubjectAccessChange from(EventEnvelope envelope, AccountAccessChangedEvent event) {
        if (envelope == null || event == null
                || !Objects.equals(envelope.eventId(), event.eventId())
                || !Objects.equals(envelope.eventType(), event.eventType())
                || !Objects.equals(envelope.key(), event.subject())) {
            throw new IllegalArgumentException("계정 접근 변경 사실의 봉투와 본문이 어긋납니다.");
        }
        return new SubjectAccessChange(event.subject(), event.changedAt());
    }
}
