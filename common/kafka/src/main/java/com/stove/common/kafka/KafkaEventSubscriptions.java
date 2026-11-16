package com.stove.common.kafka;

import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.kafka.annotation.KafkaListenerConfigurer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistrar;
import org.springframework.kafka.config.MethodKafkaListenerEndpoint;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;

/**
 * {@link EventSubscriber} 를 단 {@link EventHandler} 빈마다 Kafka 리스너 컨테이너를 하나 등록한다(Kafka adapter).
 *
 * <p>흐름: 기동 때 Spring Kafka 가 {@link #configureKafkaListeners} 를 부른다 → 핸들러 빈을 찾는다 →
 * 빈마다 {@code (토픽, 그룹)} 엔드포인트를 등록한다 → 레코드가 오면 {@link Receiver#onMessage} 가
 * {@link KafkaInboundMessages#from} 으로 바꿔 {@link EventHandler#receive} 를 부른다.
 *
 * <p>컨테이너 팩토리: 선언에 특별한 것이 없으면 Boot 기본 팩토리(공용 오류 처리기 {@code stoveKafkaErrorHandler})를 쓴다.
 * {@link EventSubscriber#noRetryFor()} 나 {@link EventSubscriber#oneAtATime()} 이 있으면 그 핸들러 전용 팩토리를 만든다
 * — 예전에 앱마다 두던 {@code RefundKafkaConfiguration} 이 하던 일이다.
 */
public class KafkaEventSubscriptions implements KafkaListenerConfigurer {

    private static final Method ON_MESSAGE = onMessage();

    private final ListableBeanFactory beans;
    private final Supplier<ConcurrentKafkaListenerContainerFactoryConfigurer> configurer;
    private final Supplier<ConsumerFactory<Object, Object>> consumers;
    private final ConsumerRecordRecoverer deadLetters;

    /** {@code configurer}·{@code consumers} 는 전용 팩토리가 필요할 때만 꺼낸다. 그때 없으면 기동을 멈춘다. */
    public KafkaEventSubscriptions(ListableBeanFactory beans,
                                   Supplier<ConcurrentKafkaListenerContainerFactoryConfigurer> configurer,
                                   Supplier<ConsumerFactory<Object, Object>> consumers,
                                   ConsumerRecordRecoverer deadLetters) {
        this.beans = beans;
        this.configurer = configurer;
        this.consumers = consumers;
        this.deadLetters = deadLetters;
    }

    @Override
    public void configureKafkaListeners(KafkaListenerEndpointRegistrar registrar) {
        DefaultMessageHandlerMethodFactory methods = new DefaultMessageHandlerMethodFactory();
        methods.afterPropertiesSet();
        for (Map.Entry<String, Object> entry : beans.getBeansWithAnnotation(EventSubscriber.class).entrySet()) {
            String name = entry.getKey();
            if (!(entry.getValue() instanceof EventHandler handler)) {
                throw new IllegalStateException("@EventSubscriber 는 EventHandler 구현에만 단다: " + name);
            }
            EventSubscriber subscription = beans.findAnnotationOnBean(name, EventSubscriber.class);
            MethodKafkaListenerEndpoint<String, String> endpoint = new MethodKafkaListenerEndpoint<>();
            endpoint.setId(name);
            endpoint.setGroupId(subscription.group());
            endpoint.setTopics(subscription.topics());
            endpoint.setBean(new Receiver(handler));
            endpoint.setMethod(ON_MESSAGE);
            endpoint.setMessageHandlerMethodFactory(methods);
            registrar.registerEndpoint(endpoint, needsOwnFactory(subscription) ? factoryFor(subscription) : null);
        }
    }

    private static boolean needsOwnFactory(EventSubscriber subscription) {
        return subscription.noRetryFor().length > 0 || subscription.oneAtATime();
    }

    /** 이 선언만의 팩토리. Boot 설정(직렬화·관측 등)은 기본 팩토리와 같고 오류 처리·가져오는 건수만 다르다. */
    ConcurrentKafkaListenerContainerFactory<Object, Object> factoryFor(EventSubscriber subscription) {
        // Boot configurer 의 고정 제네릭 시그니처다. 업무 입력을 Object 로 넓혀 전달하지 않는다.
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        ConcurrentKafkaListenerContainerFactoryConfigurer boot = configurer.get();
        ConsumerFactory<Object, Object> consumerFactory = consumers.get();
        if (boot == null || consumerFactory == null) {
            throw new IllegalStateException("전용 리스너 팩토리를 만들 Boot Kafka 설정(KafkaAutoConfiguration)이 없다");
        }
        boot.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(DeadLetterErrorHandlers.create(deadLetters, subscription.noRetryFor()));
        if (subscription.oneAtATime()) {
            Properties properties = new Properties();
            properties.setProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1");
            // 엔드포인트 초기화가 원형의 소비자 속성을 바꾸므로 실제 컨테이너에 마지막으로 건다.
            factory.setContainerCustomizer(container ->
                    container.getContainerProperties().setKafkaConsumerProperties(properties));
        }
        return factory;
    }

    private static Method onMessage() {
        try {
            return Receiver.class.getMethod("onMessage", ConsumerRecord.class);
        } catch (NoSuchMethodException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 리스너 컨테이너가 부르는 대상. Kafka 레코드를 중립 메시지로 바꿔 핸들러에 넘긴다. */
    public static final class Receiver {
        private final EventHandler handler;

        Receiver(EventHandler handler) {
            this.handler = handler;
        }

        public void onMessage(ConsumerRecord<String, String> record) {
            handler.receive(KafkaInboundMessages.from(record));
        }
    }
}
