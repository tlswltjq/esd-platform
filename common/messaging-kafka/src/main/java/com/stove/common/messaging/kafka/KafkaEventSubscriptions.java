package com.stove.common.messaging.kafka;

import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.common.event.KeyQuarantine;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.annotation.KafkaListenerConfigurer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistrar;
import org.springframework.kafka.config.MethodKafkaListenerEndpoint;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.util.backoff.BackOff;
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
 * — 예전에 앱마다 두던 {@code RefundKafkaConfiguration} 이 하던 일이다. 전용 팩토리도 빈처럼 애플리케이션 컨텍스트를
 * 받는다. 컨테이너가 시작할 때 컨텍스트에서 {@code ObservationRegistry} 를 찾기 때문이다(없으면 소비 관측이 빠진다).
 *
 * <p>구독자가 있는데 Spring Kafka 가 이 등록기를 한 번도 부르지 않았으면(예: {@code @EnableKafka} 없음) 기동을 멈춘다.
 * 조용히 아무것도 받지 않는 리스너가 가장 나쁘다.
 */
public class KafkaEventSubscriptions implements KafkaListenerConfigurer, SmartLifecycle {

    private static final Method ON_MESSAGE = onMessage();

    private final ApplicationContext beans;
    private final Supplier<ConcurrentKafkaListenerContainerFactoryConfigurer> configurer;
    private final Supplier<ConsumerFactory<Object, Object>> consumers;
    private final Supplier<ConsumerRecordRecoverer> deadLetters;
    private final Supplier<KeyQuarantine> quarantine;
    private volatile boolean configured;
    private volatile boolean running;

    /**
     * {@code configurer}·{@code consumers}·{@code deadLetters} 는 전용 팩토리가 필요할 때만 꺼낸다. 그때 없으면 기동을 멈춘다.
     *
     * @param quarantine 소비 모듈 DB 의 격리(결정 206). 있으면 모든 구독이 재시도 소진·재시도 제외 예외를 DLT 대신
     *                   거기에 보관하고 그 키를 멈춘다. 없으면(DB 없는 Store) 예전처럼 DLT 로 보낸다.
     */
    public KafkaEventSubscriptions(ApplicationContext beans,
                                   Supplier<ConcurrentKafkaListenerContainerFactoryConfigurer> configurer,
                                   Supplier<ConsumerFactory<Object, Object>> consumers,
                                   Supplier<ConsumerRecordRecoverer> deadLetters,
                                   Supplier<KeyQuarantine> quarantine) {
        this.beans = beans;
        this.configurer = configurer;
        this.consumers = consumers;
        this.deadLetters = deadLetters;
        this.quarantine = quarantine;
    }

    @Override
    public void configureKafkaListeners(KafkaListenerEndpointRegistrar registrar) {
        configured = true;
        KeyQuarantine keyQuarantine = quarantine.get();
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
            endpoint.setBean(new Receiver(handler, subscription.group(), keyQuarantine));
            endpoint.setMethod(ON_MESSAGE);
            endpoint.setMessageHandlerMethodFactory(methods);
            // 격리가 있으면 소비 그룹마다 격리 기록기가 달라 모든 구독이 전용 팩토리를 쓴다.
            boolean ownFactory = keyQuarantine != null || needsOwnFactory(subscription);
            registrar.registerEndpoint(endpoint, ownFactory ? factoryFor(subscription, keyQuarantine) : null);
        }
    }

    private static boolean needsOwnFactory(EventSubscriber subscription) {
        return subscription.noRetryFor().length > 0 || subscription.oneAtATime() || subscription.retryUntilSuccess();
    }

    /** 이 선언만의 팩토리. Boot 설정(직렬화·관측 등)은 기본 팩토리와 같고 오류 처리·가져오는 건수만 다르다. */
    ConcurrentKafkaListenerContainerFactory<Object, Object> factoryFor(EventSubscriber subscription, KeyQuarantine keyQuarantine) {
        // Boot configurer 의 고정 제네릭 시그니처다. 업무 입력을 Object 로 넓혀 전달하지 않는다.
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        ConcurrentKafkaListenerContainerFactoryConfigurer boot = configurer.get();
        ConsumerFactory<Object, Object> consumerFactory = consumers.get();
        if (boot == null || consumerFactory == null) {
            throw new IllegalStateException("전용 리스너 팩토리를 만들 Boot Kafka 설정(KafkaAutoConfiguration)이 없다");
        }
        boot.configure(factory, consumerFactory);
        // 빈이 아니라서 Spring 이 대신 넣어 주지 않는다. 빠지면 이 리스너들만 관측(추적 span)이 꺼진다.
        factory.setApplicationContext(beans);
        factory.setApplicationEventPublisher(beans);
        ConsumerRecordRecoverer recoverer = keyQuarantine != null
                ? toQuarantine(subscription.group(), keyQuarantine) : deadLetters.get();
        if (recoverer == null) {
            throw new IllegalStateException("격리(DLT)에 쓸 KafkaTemplate 이 없다");
        }
        BackOff backOff = subscription.retryUntilSuccess() ? ConsumerRetryPolicy.untilSuccess() : ConsumerRetryPolicy.backOff();
        factory.setCommonErrorHandler(DeadLetterErrorHandlers.create(recoverer, backOff, subscription.noRetryFor()));
        if (subscription.oneAtATime()) {
            Properties properties = new Properties();
            properties.setProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1");
            // 엔드포인트 초기화가 원형의 소비자 속성을 바꾸므로 실제 컨테이너에 마지막으로 건다.
            factory.setContainerCustomizer(container ->
                    container.getContainerProperties().setKafkaConsumerProperties(properties));
        }
        return factory;
    }

    /**
     * 재시도를 다 쓴(또는 재시도 제외) 레코드를 소비 모듈 DB 에 보관하고 키를 멈춘다. 보관이 실패하면 예외가 나가
     * 오류 처리기가 오프셋을 넘기지 않는다(다시 받는다).
     */
    @SuppressWarnings("unchecked")
    static ConsumerRecordRecoverer toQuarantine(String consumerGroup, KeyQuarantine keyQuarantine) {
        // 리스너 컨테이너는 문자열 레코드만 받는다(Boot 의 StringDeserializer 설정). 오류 처리기 시그니처가 와일드카드라 좁힌다.
        return (record, exception) -> keyQuarantine.quarantine(consumerGroup,
                KafkaInboundMessages.from((ConsumerRecord<String, String>) record), exception);
    }

    /** 기동 마지막(모든 빈과 리스너 등록 뒤)에 확인한다. */
    @Override
    public void start() {
        if (!configured && !beans.getBeansWithAnnotation(EventSubscriber.class).isEmpty()) {
            throw new IllegalStateException(
                    "@EventSubscriber 가 있는데 Kafka 리스너 등록이 일어나지 않았다(@EnableKafka·Boot Kafka 설정 확인)");
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static Method onMessage() {
        try {
            return Receiver.class.getMethod("onMessage", ConsumerRecord.class);
        } catch (NoSuchMethodException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * 리스너 컨테이너가 부르는 대상. Kafka 레코드를 중립 메시지로 바꿔 핸들러에 넘긴다.
     * 그 키가 멈춰 있으면 처리하지 않고 격리 뒤에 쌓는다(결정 155: 앞이 빠진 키는 뒤도 멈춘다).
     */
    public static final class Receiver {
        private final EventHandler handler;
        private final String consumerGroup;
        private final KeyQuarantine quarantine;

        Receiver(EventHandler handler) {
            this(handler, null, null);
        }

        Receiver(EventHandler handler, String consumerGroup, KeyQuarantine quarantine) {
            this.handler = handler;
            this.consumerGroup = consumerGroup;
            this.quarantine = quarantine;
        }

        public void onMessage(ConsumerRecord<String, String> record) {
            InboundMessage message = KafkaInboundMessages.from(record);
            if (quarantine != null && quarantine.stopped(consumerGroup, message.topic(), message.key())) {
                quarantine.quarantine(consumerGroup, message, null);
                return;
            }
            handler.receive(message);
        }
    }
}
