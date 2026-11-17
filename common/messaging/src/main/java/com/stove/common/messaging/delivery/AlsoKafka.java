package com.stove.common.messaging.delivery;

import com.stove.common.event.MessagePublisher;
import com.stove.common.event.OutboundMessage;
import com.stove.common.kafka.KafkaMessagePublisher;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka 가 아닌 adapter 로 발행하면서, 그 adapter 로 받을 수 없는 소비자(DB 없는 Store)를 위해 일부 토픽을 Kafka 로도 보낸다.
 * 그 토픽은 두 곳 모두 성공해야 성공이다. 그 토픽이 있는데 {@link KafkaTemplate} 이 없으면 기동을 멈춘다(PR #210 R3).
 */
public final class AlsoKafka {

    private AlsoKafka() {
    }

    /**
     * @param property 오류 메시지에 쓸 설정 이름(예: {@code stove.messaging.rabbitmq.also-kafka-topics})
     */
    public static MessagePublisher wrap(MessagePublisher primary, Set<String> alsoKafkaTopics,
                                        KafkaTemplate<String, String> template, String property) {
        if (alsoKafkaTopics.isEmpty()) {
            return primary;
        }
        if (template == null) {
            throw new IllegalStateException(property + "=" + alsoKafkaTopics + " 인데 KafkaTemplate 이 없다");
        }
        KafkaMessagePublisher kafka = new KafkaMessagePublisher(template);
        return (OutboundMessage message) -> alsoKafkaTopics.contains(message.topic())
                ? CompletableFuture.allOf(primary.publish(message), kafka.publish(message))
                : primary.publish(message);
    }
}
