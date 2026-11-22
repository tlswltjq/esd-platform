package com.stove.common.messaging.rabbitstreams;

import com.rabbitmq.stream.Consumer;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.Message;
import com.rabbitmq.stream.MessageHandler;
import com.rabbitmq.stream.NoOffsetException;
import com.rabbitmq.stream.OffsetSpecification;
import com.stove.common.event.InboundMessage;
import com.stove.common.messaging.delivery.InPlaceDelivery;
import com.stove.common.messaging.delivery.SubscribedHandler;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * 수신 포트의 RabbitMQ Streams adapter(결정 211).
 *
 * <p>흐름: 기동 때 구독 토픽의 super stream 을 선언한다({@link RabbitStreamsTopology}) → (소비 그룹, 토픽)마다 super stream 소비자 하나
 * (이름 = 그룹, 분할마다 단일 활성 소비자, 수동 위치 저장) → 메시지를 {@link InboundMessage} 로 바꿔 {@link InPlaceDelivery} 에 넘긴다 →
 * 처리(또는 격리 보관)가 끝나면 그 위치를 브로커에 저장한다.
 *
 * <p>시작 위치: 저장된 위치가 있으면 그 다음부터, 없으면(새 그룹) 분할의 처음부터 — Kafka 의 {@code auto-offset-reset: earliest} 와 같다.
 * 활성 소비자가 바뀔 때도 브로커가 새 활성 소비자에게 이 규칙으로 위치를 묻는다.
 *
 * <p>순서(결정 202): 같은 키는 같은 분할로 오고, 분할마다 한 소비자만 받는다. 실패는 그 자리에서 재시도하고(처리기를 돌려주지 않음)
 * 위치를 저장하지 않으므로 뒤 메시지가 앞지르지 않는다. 멈추는 중이면 예외를 던지고 위치를 저장하지 않는다 — 다음 활성 소비자가 그
 * 메시지부터 다시 받는다(Inbox 가 이미 처리한 것은 건너뛴다).
 *
 * <p>처리기는 클라이언트의 전달 스레드에서 돈다. 한 분할의 재시도가 다른 분할을 막지 않도록 연결마다 소비자 하나만 둔다
 * ({@code maxConsumersByConnection(1)}, 자동 구성에서).
 */
@Slf4j
public class RabbitStreamsSubscriptions implements SmartLifecycle {

    /** 기동 때(모든 빈이 만들어진 뒤) {@code @EventSubscriber} 를 찾는다. */
    private final Supplier<List<SubscribedHandler>> handlers;
    private final Environment environment;
    private final InPlaceDelivery delivery;
    private final int partitions;
    private final Duration maxAge;
    private final Duration holdStart;
    private final List<Consumer> consumers = new ArrayList<>();
    private volatile boolean running;

    public RabbitStreamsSubscriptions(Supplier<List<SubscribedHandler>> handlers, Environment environment,
                                      InPlaceDelivery delivery, int partitions, Duration maxAge) {
        this(handlers, environment, delivery, partitions, maxAge, InPlaceDelivery.HOLD_START);
    }

    RabbitStreamsSubscriptions(Supplier<List<SubscribedHandler>> handlers, Environment environment, InPlaceDelivery delivery,
                               int partitions, Duration maxAge, Duration holdStart) {
        this.handlers = handlers;
        this.environment = environment;
        this.delivery = delivery;
        this.partitions = partitions;
        this.maxAge = maxAge;
        this.holdStart = holdStart;
    }

    @Override
    public void start() {
        Map<String, SubscribedHandler> unique = new LinkedHashMap<>();
        for (SubscribedHandler handler : handlers.get()) {
            unique.put(handler.group() + "|" + handler.topic(), handler);
        }
        running = true;
        for (SubscribedHandler subscription : unique.values()) {
            RabbitStreamsTopology.declare(environment, subscription.topic(), partitions, maxAge);
            consumers.add(consumer(subscription));
        }
        log.info("RabbitMQ Streams 구독 {}개 시작 (토픽마다 분할 {}개)", unique.size(), partitions);
    }

    private Consumer consumer(SubscribedHandler subscription) {
        return environment.consumerBuilder()
                .superStream(subscription.topic())
                .name(subscription.group())
                .singleActiveConsumer()
                .manualTrackingStrategy()
                .builder()
                .consumerUpdateListener(context -> startingPoint(context.consumer()))
                .messageHandler((MessageHandler.Context context, Message message) -> {
                    delivery.deliverHolding(subscription.group(), subscription.handler(), subscription.noRetryFor(),
                            toInbound(subscription.topic(), context.stream(), context.offset(), message), () -> running, holdStart);
                    context.storeOffset();
                })
                .build();
    }

    /** 저장된 위치 다음, 없으면 분할의 처음. */
    static OffsetSpecification startingPoint(Consumer partition) {
        try {
            return OffsetSpecification.offset(partition.storedOffset() + 1);
        } catch (NoOffsetException none) {
            return OffsetSpecification.first();
        }
    }

    /** 발행 adapter 가 실은 application property 를 그대로 되살린다. 순서 키는 {@link RabbitStreamsTopology#KEY_PROPERTY}. */
    static InboundMessage toInbound(String topic, String partition, long offset, Message message) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        String key = null;
        Map<String, Object> properties = message.getApplicationProperties();
        if (properties != null) {
            for (Map.Entry<String, Object> property : properties.entrySet()) {
                if (property.getValue() == null) {
                    continue;
                }
                String value = property.getValue().toString();
                if (RabbitStreamsTopology.KEY_PROPERTY.equals(property.getKey())) {
                    key = value;
                } else {
                    headers.put(property.getKey(), List.of(value));
                }
            }
        }
        return new InboundMessage(topic, key, new String(message.getBodyAsBinary(), StandardCharsets.UTF_8), headers,
                partition + " offset=" + offset);
    }

    @Override
    public void stop() {
        running = false;
        RuntimeException failure = null;
        for (Consumer consumer : consumers) {
            try {
                consumer.close();
            } catch (RuntimeException closing) {
                if (failure == null) {
                    failure = closing;
                } else {
                    failure.addSuppressed(closing);
                }
            }
        }
        consumers.clear();
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
