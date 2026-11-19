package com.stove.common.kafka;

import com.stove.common.event.MessagePublisher;
import com.stove.common.event.OutboundMessage;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 발행 포트의 Kafka adapter. 순서 키를 Kafka 메시지 키로 써서 같은 키가 같은 파티션에 들어가게 한다
 * — 파티션 안의 순서가 결정 202 의 "같은 키는 보낸 순서대로"를 만든다.
 *
 * <p>추적 헤더는 릴레이가 직접 싣는다. {@code spring.kafka.template.observation-enabled} 는 꺼 둔다.
 */
public class KafkaMessagePublisher implements MessagePublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaMessagePublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public CompletableFuture<Void> publish(OutboundMessage message) {
        return kafkaTemplate.send(toRecord(message)).thenApply(sent -> null);
    }

    static ProducerRecord<String, String> toRecord(OutboundMessage message) {
        ProducerRecord<String, String> record = new ProducerRecord<>(message.topic(), message.key(), message.payload());
        for (Map.Entry<String, String> header : message.headers().entrySet()) {
            record.headers().add(header.getKey(), header.getValue().getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
