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
     * <b>단일 노드 전용</b>(PR #222 R4): 브로커가 알려 주는 주소(컨테이너 호스트 이름일 수 있다) 대신 늘 설정한 주소로 붙는다.
     * 클러스터에서는 분할 리더가 다른 노드에 있어도 이 주소로만 붙어 연결·복구가 실패할 수 있다 — 클러스터로 옮기면
     * 노드별 주소 매핑({@code addressResolver})이나 {@code stream.advertised_host} 설정이 필요하다.
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

    /**
     * producer 는 {@link Environment#close()} 가 함께 닫는다(stream-client 가 자기가 만든 producer·consumer 를 모두 닫는다) —
     * 미러 래퍼가 발행기를 감싸도 종료 때 남지 않는다(PR #222 R2).
     */
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
