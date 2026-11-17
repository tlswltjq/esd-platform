package com.stove.common.messaging.rabbitmq;

import com.stove.common.event.InboundMessage;
import com.stove.common.messaging.delivery.InPlaceDelivery;
import com.stove.common.messaging.delivery.SubscribedHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.SmartLifecycle;

/**
 * 수신 포트의 RabbitMQ adapter(결정 203).
 *
 * <p>흐름: 기동 때 (소비 그룹, 토픽)마다 exchange·큐 N개·바인딩을 선언한다({@link RabbitTopology}) →
 * 큐마다 리스너 컨테이너 하나(소비자 1, 수동 아닌 AUTO ack) → 메시지를 {@link InboundMessage} 로 바꿔 {@link InPlaceDelivery} 에 넘긴다 →
 * 처리(또는 격리 보관)가 끝나 리스너가 정상 반환하면 컨테이너가 ack 한다.
 *
 * <p>순서(결정 202): 같은 키는 같은 큐로 오고, 큐는 {@code x-single-active-consumer} 라 인스턴스가 여럿이어도 한 소비자만 받는다.
 * 실패는 그 자리에서 재시도하므로(요청 큐로 되돌리지 않음) 뒤 메시지가 앞지르지 않는다.
 * 격리 보관이 실패하면 리스너가 예외를 던지고 컨테이너가 메시지를 큐로 되돌려(requeue) 다시 받는다.
 */
@Slf4j
public class RabbitSubscriptions implements SmartLifecycle {

    /** 기동 때(모든 빈이 만들어진 뒤) {@code @EventSubscriber} 를 찾는다. */
    private final Supplier<List<SubscribedHandler>> handlers;
    private final ConnectionFactory connectionFactory;
    private final AmqpAdmin admin;
    private final InPlaceDelivery delivery;
    private final int queuesPerTopic;
    private final int prefetch;
    private final List<SimpleMessageListenerContainer> containers = new ArrayList<>();
    private volatile boolean running;

    public RabbitSubscriptions(Supplier<List<SubscribedHandler>> handlers, ConnectionFactory connectionFactory, AmqpAdmin admin,
                               InPlaceDelivery delivery, int queuesPerTopic, int prefetch) {
        this.handlers = handlers;
        this.connectionFactory = connectionFactory;
        this.admin = admin;
        this.delivery = delivery;
        this.queuesPerTopic = queuesPerTopic;
        this.prefetch = prefetch;
    }

    @Override
    public void start() {
        Map<String, SubscribedHandler> unique = new LinkedHashMap<>();
        for (SubscribedHandler handler : handlers.get()) {
            unique.put(handler.group() + "|" + handler.topic(), handler);
        }
        for (SubscribedHandler subscription : unique.values()) {
            DirectExchange exchange = new DirectExchange(RabbitTopology.exchange(subscription.topic()), true, false);
            admin.declareExchange(exchange);
            for (int shard = 0; shard < queuesPerTopic; shard++) {
                Queue queue = QueueBuilder.durable(RabbitTopology.queue(subscription.group(), subscription.topic(), shard))
                        .singleActiveConsumer()
                        .build();
                admin.declareQueue(queue);
                Binding binding = BindingBuilder.bind(queue).to(exchange).with(RabbitTopology.routingKey(shard));
                admin.declareBinding(binding);
                containers.add(container(subscription, queue.getName()));
            }
        }
        for (SimpleMessageListenerContainer container : containers) {
            container.start();
        }
        running = true;
        log.info("RabbitMQ 구독 {}개 시작 (구독마다 큐 {}개)", unique.size(), queuesPerTopic);
    }

    private SimpleMessageListenerContainer container(SubscribedHandler subscription, String queueName) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(queueName);
        container.setConcurrentConsumers(1);
        container.setMaxConcurrentConsumers(1);
        container.setPrefetchCount(prefetch);
        container.setAcknowledgeMode(AcknowledgeMode.AUTO);
        container.setDefaultRequeueRejected(true);
        container.setMessageListener((Message message) -> delivery.deliver(subscription.group(), subscription.handler(),
                subscription.noRetryFor(), toInbound(subscription.topic(), queueName, message)));
        container.afterPropertiesSet();
        return container;
    }

    /** 발행 adapter 가 실은 헤더를 그대로 되살린다. 순서 키는 {@link RabbitTopology#KEY_HEADER}. */
    static InboundMessage toInbound(String topic, String queueName, Message message) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        String key = null;
        for (Map.Entry<String, Object> header : message.getMessageProperties().getHeaders().entrySet()) {
            if (header.getValue() == null) {
                continue;
            }
            String value = header.getValue().toString();
            if (RabbitTopology.KEY_HEADER.equals(header.getKey())) {
                key = value;
            } else {
                headers.put(header.getKey(), List.of(value));
            }
        }
        return new InboundMessage(topic, key, new String(message.getBody(), StandardCharsets.UTF_8), headers,
                queueName + " tag=" + message.getMessageProperties().getDeliveryTag());
    }

    @Override
    public void stop() {
        running = false;
        for (SimpleMessageListenerContainer container : containers) {
            container.stop();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
