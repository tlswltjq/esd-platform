package com.stove.common.messaging.kafka;

import com.stove.common.event.InboundMessage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

/** Kafka 레코드를 브로커 중립 {@link InboundMessage} 로 바꾼다. Kafka 를 아는 곳은 adapter 뿐이다. */
public final class KafkaInboundMessages {

    private KafkaInboundMessages() {
    }

    /** 같은 이름의 헤더는 받은 순서대로 모두 둔다({@code header()} 는 Kafka {@code lastHeader} 처럼 마지막 값). 값이 없는(null) 헤더는 빈 문자열로 둔다 — 버리면 "헤더가 없다"와 "헤더가 깨졌다"가 같아진다. */
    public static InboundMessage from(ConsumerRecord<String, String> record) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (Header header : record.headers()) {
            String value = header.value() == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
            headers.computeIfAbsent(header.key(), name -> new ArrayList<>()).add(value);
        }
        String origin = "partition=%d offset=%d".formatted(record.partition(), record.offset());
        return new InboundMessage(record.topic(), record.key(), record.value(), headers, origin);
    }
}
