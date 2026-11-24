package com.stove.common.contract.payload;

import com.stove.common.contract.DomainEvent;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import java.time.Instant;
import java.util.UUID;

/**
 * 계정 접근 변경 사실([결정 180]·[결정 225]). 운영자가 계정의 상태나 역할을 바꿔, 그 계정의 이전 접근 근거(발급된 토큰·세션)가
 * 더는 유효하지 않다는 것만 뜻한다. 받는 쪽: 웹 BFF는 그 {@code subject}의 세션·토큰 기록을 지우고, API 게이트웨이는
 * {@code changedAt} 전에 발급된 토큰({@code iat} &lt; {@code changedAt})을 거절한다.
 *
 * @param eventId 서비스 간 전달 시 유지하는 이벤트 고유 식별자.
 * @param occurredAt 업무 행동을 기록한 시각.
 * @param subject 접근이 바뀐 계정의 인증 주체 문자열.
 * @param changedAt 변경 시각(Auth 시계). 토큰의 {@code iat}와 같은 시계라 받는 쪽이 그대로 비교한다.
 * @param changeKind 바뀐 것: {@link #STATUS} 또는 {@link #ROLE}.
 */
public record AccountAccessChangedEvent(
        String eventId,
        Instant occurredAt,
        String subject,
        Instant changedAt,
        String changeKind
) implements DomainEvent {
    /** 계정 상태(정지·해제·종료)가 바뀌었다. */
    public static final String STATUS = "STATUS";
    /** 계정 역할이 부여·회수됐다. */
    public static final String ROLE = "ROLE";

    public AccountAccessChangedEvent {
        requireText(eventId, "eventId");
        requireText(subject, "subject");
        if (occurredAt == null || changedAt == null) {
            throw new IllegalArgumentException("occurredAt·changedAt은 null일 수 없습니다.");
        }
        if (!STATUS.equals(changeKind) && !ROLE.equals(changeKind)) {
            throw new IllegalArgumentException("changeKind는 STATUS 또는 ROLE이어야 합니다: " + changeKind);
        }
    }

    public static AccountAccessChangedEvent of(String subject, Instant changedAt, String changeKind) {
        return new AccountAccessChangedEvent(UUID.randomUUID().toString(), changedAt, subject, changedAt, changeKind);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "는 비어 있을 수 없습니다.");
        }
    }

    @Override public String eventType() { return EventType.ACCOUNT_ACCESS_CHANGED; }
    @Override public String topic() { return Topics.AUTH; }
    @Override public String partitionKey() { return subject; }
}
