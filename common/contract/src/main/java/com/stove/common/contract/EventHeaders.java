package com.stove.common.contract;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

/** Kafka 메시지 헤더 규약. 컨슈머는 body 를 파싱하기 전에 헤더만 보고 라우팅/멱등 판단을 한다. */
public final class EventHeaders {

    public static final String EVENT_ID = "eventId";
    public static final String EVENT_TYPE = "eventType";
    public static final String OCCURRED_AT = "occurredAt";

    /**
     * 릴레이가 브로커로 보내기 직전 시각(ISO-8601). 브로커 구간 지연 측정용이며 업무 판단에 쓰지 않는다([결정 204] B).
     * 행의 {@code outbox_event.published_at}과 같은 값이다. 브로커 구현과 무관하게 같은 이름으로 싣는다.
     */
    public static final String PUBLISHED_AT = "x-published-at";

    /**
     * W3C Trace Context. <b>이름을 우리가 정하지 않는다</b> — 표준이라 바꾸면 추적이 조용히 끊긴다.
     * 자동 계측만으로는 채워지지 않는다(Outbox 의 발행자는 요청 스레드가 아니다).
     */
    public static final String TRACE_PARENT = "traceparent";

    private EventHeaders() {
    }

    /**
     * 그 이름의 마지막 값(Kafka {@code lastHeader} 와 같은 선택). 없으면 {@code null}.
     * 값이 없는(null) 헤더는 빈 문자열로 읽는다 — 버리면 "헤더가 없다"와 "헤더가 깨졌다"가 같아진다.
     */
    public static String lastValue(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        return header == null ? null : text(header);
    }

    /** 그 이름의 모든 값(받은 순서). 없으면 빈 목록. 중복 헤더를 거부하는 리스너가 쓴다. */
    public static List<String> values(Headers headers, String name) {
        List<String> values = new ArrayList<>();
        for (Header header : headers.headers(name)) {
            values.add(text(header));
        }
        return values;
    }

    /** 모든 헤더를 이름 → 값 목록(받은 순서)으로. 격리 보관이 원래 레코드를 다시 만들 때 쓴다. */
    public static Map<String, List<String>> all(Headers headers) {
        Map<String, List<String>> all = new LinkedHashMap<>();
        for (Header header : headers) {
            all.computeIfAbsent(header.key(), name -> new ArrayList<>()).add(text(header));
        }
        return all;
    }

    private static String text(Header header) {
        return header.value() == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
    }
}
