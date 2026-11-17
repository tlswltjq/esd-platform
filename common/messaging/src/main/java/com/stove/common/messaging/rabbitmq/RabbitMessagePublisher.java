package com.stove.common.messaging.rabbitmq;

import com.stove.common.event.MessagePublisher;
import com.stove.common.event.OutboundMessage;
import com.stove.common.kafka.EventHeaders;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * 발행 포트의 RabbitMQ adapter(결정 203).
 *
 * <p>흐름: 토픽 exchange 를 (처음 한 번) 선언한다 → 순서 키 해시로 큐 번호를 골라 라우팅 키로 쓴다 →
 * 지속(persistent) 메시지로 보낸다 → 브로커의 publisher confirm(ack)을 받으면 성공. 릴레이는 그때 행을 {@code SENT} 로 바꾼다.
 * nack·연결 실패면 실패한 Future 를 돌려줘 릴레이가 그 키를 다시 보낸다.
 *
 * <p>소비 큐가 아직 없으면 브로커는 메시지를 버린다(Kafka 처럼 로그를 남기지 않는다). 소비 앱이 기동하며 큐를 선언하므로 운영에서는
 * 소비 앱을 먼저 띄운다 — RabbitMQ 를 고를 때 따라오는 조건이다.
 */
public class RabbitMessagePublisher implements MessagePublisher {

    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin admin;
    private final int queuesPerTopic;
    private final Set<String> declared = ConcurrentHashMap.newKeySet();

    public RabbitMessagePublisher(RabbitTemplate rabbitTemplate, AmqpAdmin admin, int queuesPerTopic) {
        this.rabbitTemplate = rabbitTemplate;
        this.admin = admin;
        this.queuesPerTopic = queuesPerTopic;
    }

    @Override
    public CompletableFuture<Void> publish(OutboundMessage message) {
        try {
            String exchange = RabbitTopology.exchange(message.topic());
            if (!declared.contains(exchange)) {
                admin.declareExchange(new DirectExchange(exchange, true, false));
                declared.add(exchange);
            }
            CorrelationData correlation = new CorrelationData(message.headers().get(EventHeaders.EVENT_ID));
            rabbitTemplate.send(exchange, RabbitTopology.routingKey(RabbitTopology.shard(message.key(), queuesPerTopic)),
                    toAmqp(message), correlation);
            return correlation.getFuture().thenApply(confirm -> {
                if (!confirm.isAck()) {
                    throw new IllegalStateException("RabbitMQ 가 발행을 거절했다(nack): " + confirm.getReason());
                }
                return null;
            });
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    static Message toAmqp(OutboundMessage message) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(message.headers().get(EventHeaders.EVENT_ID));
        properties.setHeader(RabbitTopology.KEY_HEADER, message.key());
        for (Map.Entry<String, String> header : message.headers().entrySet()) {
            properties.setHeader(header.getKey(), header.getValue());
        }
        return new Message(message.payload().getBytes(StandardCharsets.UTF_8), properties);
    }
}
