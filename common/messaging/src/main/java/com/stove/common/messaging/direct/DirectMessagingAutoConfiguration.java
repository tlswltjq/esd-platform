package com.stove.common.messaging.direct;

import com.stove.common.event.KeyQuarantine;
import com.stove.common.event.MessagePublisher;
import com.stove.common.kafka.KafkaConsumerAutoConfiguration;
import com.stove.common.kafka.KafkaMessagePublisher;
import com.stove.common.messaging.MessagingAutoConfiguration;
import com.stove.common.messaging.config.DirectMessagingProperties;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 브로커 없음 기준선(결정 203)을 켠다: {@code stove.messaging.broker=direct}.
 * Kafka 의 발행 포트 기본 빈보다 먼저 와서 그쪽을 물러나게 하고, Kafka 구독 등록은 같은 설정으로 꺼진다.
 */
@AutoConfiguration(after = MessagingAutoConfiguration.class, before = KafkaConsumerAutoConfiguration.class)
@ConditionalOnProperty(prefix = "stove.messaging", name = "broker", havingValue = "direct")
@EnableConfigurationProperties(DirectMessagingProperties.class)
public class DirectMessagingAutoConfiguration {

    @Bean
    public MessagePublisher directMessagePublisher(DirectMessagingProperties properties,
                                                   ObjectProvider<KafkaTemplate<String, String>> kafkaTemplate) {
        KafkaTemplate<String, String> template = kafkaTemplate.getIfAvailable();
        MessagePublisher fallback = template == null || properties.alsoKafkaTopics().isEmpty()
                ? null : new KafkaMessagePublisher(template);
        return new DirectMessagePublisher(properties.alsoKafkaTopics(), fallback);
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
