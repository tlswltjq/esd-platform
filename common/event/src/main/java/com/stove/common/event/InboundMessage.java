package com.stove.common.event;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 브로커에서 받은 메시지 한 건. 어떤 브로커에서 왔는지 모르는 형태다.
 *
 * <p>adapter 가 브로커 레코드(Kafka {@code ConsumerRecord}, RabbitMQ {@code Message} 등)를 이것으로 바꿔
 * {@link EventHandler#receive(InboundMessage)} 에 넘긴다. 봉투 검증({@code EventEnvelope.from})은 그 뒤 리스너가 한다.
 *
 * @param topic 업무 토픽 이름({@code Topics}). 브로커가 다른 이름(큐·스트림)을 쓰면 adapter 가 이 이름으로 되돌린다.
 * @param key 순서 키(주문번호·상품코드·주체). 같은 키는 보낸 순서대로 도착한다(결정 202).
 * @param payload 이벤트 본문(JSON 문자열).
 * @param headers 계약 헤더({@code x-event-id} 등). 이름마다 받은 순서대로 모든 값을 둔다 — 중복 헤더를 거부하는 리스너가 있다.
 * @param origin 오류 메시지에 남길 브로커 위치(예: {@code partition=3 offset=10}). 업무 판단에 쓰지 않는다.
 */
public record InboundMessage(String topic, String key, String payload, Map<String, List<String>> headers, String origin) {

    public InboundMessage {
        Objects.requireNonNull(topic, "topic");
        Map<String, List<String>> copy = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        }
        headers = Map.copyOf(copy);
        origin = origin == null ? "" : origin;
    }

    /** 헤더의 마지막 값. 없으면 {@code null}. */
    public String header(String name) {
        List<String> values = headers.getOrDefault(name, List.of());
        return values.isEmpty() ? null : values.get(values.size() - 1);
    }

    /** 헤더의 모든 값(받은 순서). 없으면 빈 목록. */
    public List<String> headerValues(String name) {
        return headers.getOrDefault(name, List.of());
    }
}
