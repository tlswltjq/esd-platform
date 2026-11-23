package com.stove.common.messaging.rabbitstreams;

import com.rabbitmq.stream.ConfirmationStatus;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.Message;
import com.rabbitmq.stream.MessageBuilder;
import com.rabbitmq.stream.Producer;
import com.stove.common.contract.EventHeaders;
import com.stove.common.event.MessagePublisher;
import com.stove.common.event.OutboundMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 발행 포트의 RabbitMQ Streams adapter(결정 211).
 *
 * <p>흐름: 토픽 super stream 을 (처음 한 번) 선언한다 → 토픽마다 super stream producer 하나가 순서 키 해시로 분할을 고른다 →
 * 브로커 confirm 이 오면 성공. 릴레이는 그때 행을 {@code SENT} 로 바꾼다. 거절·연결 실패·{@link #CONFIRM_TIMEOUT} 초과면
 * 실패한 Future 라 릴레이가 그 키를 다시 보낸다.
 *
 * <p>받는 소비자가 아직 없어도 분할 로그에 남으므로, classic queue adapter 처럼 "받을 큐가 없음"을 실패로 다룰 필요가 없다.
 */
public class RabbitStreamsPublisher implements MessagePublisher, AutoCloseable {

    /** confirm 을 이보다 오래 기다리지 않는다. classic queue adapter 와 같은 값이다. */
    static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(10);

    private final Environment environment;
    private final int partitions;
    private final Duration maxAge;
    private final Map<String, Producer> producers = new ConcurrentHashMap<>();

    public RabbitStreamsPublisher(Environment environment, int partitions, Duration maxAge) {
        this.environment = environment;
        this.partitions = partitions;
        this.maxAge = maxAge;
    }

    @Override
    public CompletableFuture<Void> publish(OutboundMessage message) {
        CompletableFuture<Void> confirmed = new CompletableFuture<>();
        try {
            Producer producer = producers.computeIfAbsent(message.topic(), this::producer);
            producer.send(toStream(producer.messageBuilder(), message), (ConfirmationStatus status) -> {
                if (status.isConfirmed()) {
                    confirmed.complete(null);
                } else {
                    confirmed.completeExceptionally(new IllegalStateException(
                            "RabbitMQ Streams 가 발행을 확인하지 않았다: 코드 " + status.getCode()));
                }
            });
        } catch (RuntimeException failure) {
            confirmed.completeExceptionally(failure);
        }
        return confirmed.orTimeout(CONFIRM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private Producer producer(String topic) {
        RabbitStreamsTopology.declare(environment, topic, partitions, maxAge);
        return environment.producerBuilder()
                .superStream(topic)
                .routing(RabbitStreamsPublisher::routingKeyOf)
                .hash(RabbitStreamsTopology::hash)
                .producerBuilder()
                .build();
    }

    private static String routingKeyOf(Message message) {
        Object key = message.getApplicationProperties() == null ? null
                : message.getApplicationProperties().get(RabbitStreamsTopology.KEY_PROPERTY);
        return RabbitStreamsTopology.routingKey(key == null ? null : key.toString());
    }

    static Message toStream(MessageBuilder builder, OutboundMessage message) {
        builder.properties()
                .messageId(message.headers().get(EventHeaders.EVENT_ID))
                .contentType("application/json")
                .contentEncoding(StandardCharsets.UTF_8.name());
        MessageBuilder.ApplicationPropertiesBuilder properties = builder.applicationProperties();
        if (message.key() != null) {
            // 키가 없는 메시지는 이 속성을 싣지 않는다 — 받는 쪽에서 빈 문자열이 아니라 null 로 되살린다.
            properties.entry(RabbitStreamsTopology.KEY_PROPERTY, message.key());
        }
        for (Map.Entry<String, String> header : message.headers().entrySet()) {
            properties.entry(header.getKey(), header.getValue());
        }
        return builder.addData(message.payload().getBytes(StandardCharsets.UTF_8)).build();
    }

    @Override
    public void close() {
        for (Producer producer : producers.values()) {
            producer.close();
        }
        producers.clear();
    }
}
