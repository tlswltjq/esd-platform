package com.stove.common.messaging.rabbitmq;

import com.stove.common.event.MessagePublisher;
import com.stove.common.event.OutboundMessage;
import com.stove.common.kafka.EventHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * 발행 포트의 RabbitMQ adapter(결정 203).
 *
 * <p>흐름: 토픽 exchange 를 (처음 한 번) 선언한다 → 순서 키 해시로 큐 번호를 골라 라우팅 키로 쓴다 →
 * 지속(persistent)·mandatory 메시지로 보낸다 → 브로커 confirm(ack)이 오고 되돌아온(returned) 메시지가 없으면 성공.
 * 릴레이는 그때 행을 {@code SENT} 로 바꾼다. nack·되돌아옴·연결 실패·{@link #CONFIRM_TIMEOUT} 초과면 실패한 Future 라
 * 릴레이가 그 키를 다시 보낸다.
 *
 * <p>되돌아옴 = 그 라우팅 키에 묶인 큐가 없다(소비 앱이 아직 큐를 선언하지 않음). 성공으로 치면 {@code SENT} 뒤에 사라지므로
 * 실패로 둔다(PR #211 R3). 이 adapter 로 받는 소비자가 없는 토픽({@code optionalTopics}, 예: 아무도 받지 않는 auth,
 * Kafka 로만 받는 catalog)만 되돌아와도 성공으로 친다 — Kafka 에서도 그런 토픽은 아무도 읽지 않는다.
 */
public class RabbitMessagePublisher implements MessagePublisher {

    /** confirm 을 이보다 오래 기다리지 않는다. 연결이 끊겨 confirm 이 영영 오지 않으면 릴레이가 멈추지 않게(PR #211 R2). */
    static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(10);

    private final RabbitTemplate required;
    private final RabbitTemplate optional;
    private final AmqpAdmin admin;
    private final int queuesPerTopic;
    private final Set<String> optionalTopics;
    private final Set<String> declared = ConcurrentHashMap.newKeySet();

    public RabbitMessagePublisher(ConnectionFactory connectionFactory, AmqpAdmin admin, int queuesPerTopic,
                                  Set<String> optionalTopics) {
        if (!connectionFactory.isPublisherConfirms() || !connectionFactory.isPublisherReturns()) {
            throw new IllegalStateException("RabbitMQ adapter 는 spring.rabbitmq.publisher-confirm-type=correlated 와"
                    + " spring.rabbitmq.publisher-returns=true 가 필요하다 — 없으면 발행 결과를 알 수 없다");
        }
        this.required = new RabbitTemplate(connectionFactory);
        this.required.setMandatory(true);
        this.optional = new RabbitTemplate(connectionFactory);
        this.admin = admin;
        this.queuesPerTopic = queuesPerTopic;
        this.optionalTopics = Set.copyOf(optionalTopics);
    }

    @Override
    public CompletableFuture<Void> publish(OutboundMessage message) {
        try {
            String exchange = RabbitTopology.exchange(message.topic());
            if (!declared.contains(exchange)) {
                admin.declareExchange(new DirectExchange(exchange, true, false));
                declared.add(exchange);
            }
            boolean mustRoute = !optionalTopics.contains(message.topic());
            CorrelationData correlation = new CorrelationData(message.headers().get(EventHeaders.EVENT_ID));
            (mustRoute ? required : optional).send(exchange,
                    RabbitTopology.routingKey(RabbitTopology.shard(message.key(), queuesPerTopic)), toAmqp(message), correlation);
            return correlation.getFuture()
                    .orTimeout(CONFIRM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .thenApply(confirm -> {
                        if (!confirm.isAck()) {
                            throw new IllegalStateException("RabbitMQ 가 발행을 거절했다(nack): " + confirm.getReason());
                        }
                        if (mustRoute && correlation.getReturned() != null) {
                            throw new IllegalStateException("받을 큐가 없어 되돌아왔다(소비 앱이 아직 큐를 선언하지 않음): "
                                    + exchange + " " + correlation.getReturned().getRoutingKey());
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
