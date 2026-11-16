package com.stove.common.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 브로커로 보낼 메시지 한 건. Outbox 릴레이가 {@code outbox_event} 한 행으로 만든다.
 *
 * @param topic 업무 토픽 이름({@code Topics}).
 * @param key 순서 키. 같은 키는 보낸 순서대로 도착해야 한다(결정 202).
 * @param payload 이벤트 본문(JSON 문자열).
 * @param headers 계약 헤더. 넣은 순서를 지킨다.
 */
public record OutboundMessage(String topic, String key, String payload, Map<String, String> headers) {

    public OutboundMessage {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(key, "key");
        headers = headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }
}
