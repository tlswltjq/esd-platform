package com.stove.common.messaging.rabbitstreams;

import com.rabbitmq.stream.Address;
import com.rabbitmq.stream.Environment;
import com.stove.common.event.KeyQuarantine;
import com.stove.common.event.MessagePublisher;
import com.stove.common.event.MirrorPublisher;
import com.stove.common.messaging.MessagingAutoConfiguration;
import com.stove.common.messaging.delivery.InPlaceDelivery;
import com.stove.common.messaging.delivery.MirroredTopics;
import com.stove.common.messaging.delivery.SubscribedHandler;
import com.stove.common.messaging.rabbitstreams.config.RabbitStreamsMessagingProperties;
import com.stove.common.messaging.trace.MicrometerReplayTracing;
import com.stove.common.messaging.trace.ReplayTracing;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * RabbitMQ Streams adapter(결정 211)를 켠다: {@code stove.messaging.broker=rabbitmq-streams}.
 * Kafka 의 발행 포트·구독 등록은 같은 설정(broker)으로 꺼진다(결정 209).
 */
@AutoConfiguration(after = MessagingAutoConfiguration.class)
@ConditionalOnProperty(prefix = "stove.messaging", name = "broker", havingValue = "rabbitmq-streams")
@EnableConfigurationProperties(RabbitStreamsMessagingProperties.class)
public class RabbitStreamsMessagingAutoConfiguration {

    @Bean(destroyMethod = "close")
    public Environment rabbitStreamsEnvironment(RabbitStreamsMessagingProperties properties) {
        return environment(properties.host(), properties.port(), properties.username(), properties.password());
    }

    /**
     * 단일 노드 기준: 브로커가 알려 주는 주소(컨테이너 호스트 이름일 수 있다) 대신 늘 설정한 주소로 붙는다.
     * 연결마다 소비자 하나 — 한 분할의 제자리 재시도가 같은 연결의 다른 분할 전달을 막지 않게 한다.
     */
    public static Environment environment(String host, int port, String username, String password) {
        Address fixed = new Address(host, port);
        return Environment.builder()
                .host(host)
                .port(port)
                .username(username)
                .password(password)
                .addressResolver(advertised -> fixed)
                .maxConsumersByConnection(1)
                .build();
    }

    @Bean
    public MessagePublisher rabbitStreamsMessagePublisher(Environment environment, RabbitStreamsMessagingProperties properties,
                                                          ObjectProvider<MirrorPublisher> mirror) {
        RabbitStreamsPublisher streams = new RabbitStreamsPublisher(environment, properties.partitionsPerTopic(),
                properties.maxAge());
        return MirroredTopics.wrap(streams, properties.alsoKafkaTopics(), mirror.getIfAvailable(),
                "stove.messaging.rabbitmq-streams.also-kafka-topics");
    }

    @Bean
    public RabbitStreamsSubscriptions rabbitStreamsSubscriptions(ApplicationContext context, Environment environment,
                                                                 KeyQuarantine quarantine,
                                                                 RabbitStreamsMessagingProperties properties,
                                                                 ObjectProvider<Tracer> tracer,
                                                                 ObjectProvider<Propagator> propagator) {
        Tracer availableTracer = tracer.getIfAvailable();
        Propagator availablePropagator = propagator.getIfAvailable();
        ReplayTracing tracing = availableTracer == null || availablePropagator == null
                ? ReplayTracing.NONE : new MicrometerReplayTracing(availableTracer, availablePropagator);
        return new RabbitStreamsSubscriptions(() -> SubscribedHandler.discover(context), environment,
                new InPlaceDelivery(quarantine, tracing, InPlaceDelivery.RETRY_DELAYS),
                properties.partitionsPerTopic(), properties.maxAge());
    }
}
