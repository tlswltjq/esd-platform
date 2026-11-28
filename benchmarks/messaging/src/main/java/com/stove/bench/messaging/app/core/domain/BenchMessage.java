package com.stove.bench.messaging.app.core.domain;

/**
 * 시험 리스너가 받은 메시지의 내부 입력. 헤더 검증은 {@code api.listener.BenchMessageMapper}가 한다.
 *
 * @param eventId 메시지 ID(헤더 {@code eventId}, UUID). Inbox 멱등 키다.
 * @param key 순서 키.
 * @param sentAtMicros 발행 요청 시각(헤더 {@code x-bench-sent-at}, epoch µs, 발생기 벽시계).
 * @param failure 주입 실패.
 * @param payloadBytes 본문 UTF-8 바이트 수.
 */
public record BenchMessage(String eventId, String key, long sentAtMicros, FailurePlan failure, int payloadBytes) {

    public static final String EVENT_TYPE = "BenchMessage";
    public static final String SENT_AT_HEADER = "x-bench-sent-at";

    public BenchMessage {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("메시지 ID가 없다");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("순서 키가 없다 eventId=" + eventId);
        }
        if (sentAtMicros <= 0) {
            throw new IllegalArgumentException("발행 요청 시각이 없다 eventId=" + eventId);
        }
        if (failure == null) {
            throw new IllegalArgumentException("주입 실패 계획이 없다 eventId=" + eventId);
        }
        if (payloadBytes < 0) {
            throw new IllegalArgumentException("본문 크기가 음수다 eventId=" + eventId);
        }
    }
}
