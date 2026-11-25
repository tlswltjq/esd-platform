package com.stove.webbff.core.domain;

import java.time.Instant;

/**
 * 계정 접근 변경 사실의 내부 입력(결정 180·225). 그 {@code subject}의 BFF 세션은 더는 유효하지 않다.
 *
 * @param subject 접근이 바뀐 계정.
 * @param changedAt 변경 시각(Auth 시계). 소비 지연을 잴 때 쓴다.
 */
public record SubjectAccessChange(String subject, Instant changedAt) {

    public SubjectAccessChange {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject는 비어 있을 수 없습니다.");
        }
        if (changedAt == null) {
            throw new IllegalArgumentException("changedAt은 null일 수 없습니다.");
        }
    }
}
