package com.stove.common.messaging.direct;

import com.stove.common.event.KeyQuarantine;
import com.stove.common.event.MessagePublisher;
import com.stove.common.event.MirrorPublisher;
import com.stove.common.messaging.MessagingAutoConfiguration;
import com.stove.common.messaging.delivery.MirroredTopics;
import com.stove.common.messaging.direct.config.DirectMessagingProperties;
import com.stove.common.messaging.trace.MicrometerReplayTracing;
import com.stove.common.messaging.trace.ReplayTracing;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 브로커 없음 기준선(결정 203)을 켠다: {@code stove.messaging.broker=direct}.
 * Kafka 의 발행 포트·구독 등록은 같은 설정(broker)으로 꺼진다(결정 209 — 자동 구성 순서에 기대지 않는다).
 */
@AutoConfiguration(after = MessagingAutoConfiguration.class)
@ConditionalOnProperty(prefix = "stove.messaging", name = "broker", havingValue = "direct")
@EnableConfigurationProperties(DirectMessagingProperties.class)
public class DirectMessagingAutoConfiguration {

    @Bean
    public MessagePublisher directMessagePublisher(DirectMessagingProperties properties,
                                                   ObjectProvider<MirrorPublisher> mirror) {
        return publisher(properties.alsoKafkaTopics(), mirror.getIfAvailable());
    }

    /** 미러할 토픽이 있는데 미러 발행기가 없으면 기동을 멈춘다 — 조용히 SENT 로만 남기지 않는다(PR #210 R3). */
    static DirectMessagePublisher publisher(Set<String> alsoKafkaTopics, MirrorPublisher mirror) {
        MirroredTopics.requireMirror(alsoKafkaTopics, mirror, "stove.messaging.direct.also-kafka-topics");
        return new DirectMessagePublisher(alsoKafkaTopics, alsoKafkaTopics.isEmpty() ? null : mirror::publish);
    }

    @Bean
    public DirectSubscriptions directSubscriptions(ApplicationContext context, JdbcTemplate jdbc, KeyQuarantine quarantine,
                                                   DirectMessagingProperties properties,
                                                   ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        Tracer availableTracer = tracer.getIfAvailable();
        Propagator availablePropagator = propagator.getIfAvailable();
        ReplayTracing tracing = availableTracer == null || availablePropagator == null
                ? ReplayTracing.NONE : new MicrometerReplayTracing(availableTracer, availablePropagator);
        return new DirectSubscriptions(context, jdbc, quarantine, tracing, properties.sources(),
                properties.pollIntervalMs(), properties.batchSize());
    }
}
