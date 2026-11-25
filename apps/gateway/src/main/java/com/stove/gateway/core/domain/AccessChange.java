package com.stove.gateway.core.domain;

import java.time.Instant;

/**
 * 계정 접근 변경 사실의 내부 입력(결정 180·225). {@code changedAt} 전에 발급된 그 {@code subject}의 토큰은 더는 유효하지 않다.
 *
 * @param eventId 멱등 키.
 * @param subject 접근이 바뀐 계정.
 * @param changedAt 변경 시각(Auth 시계, JWT {@code iat}와 같은 시계).
 */
public record AccessChange(String eventId, String subject, Instant changedAt) {

    public AccessChange {
        requireText(eventId, "eventId");
        requireText(subject, "subject");
        if (changedAt == null) {
            throw new IllegalArgumentException("changedAt은 null일 수 없습니다.");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "는 비어 있을 수 없습니다.");
        }
    }
}
