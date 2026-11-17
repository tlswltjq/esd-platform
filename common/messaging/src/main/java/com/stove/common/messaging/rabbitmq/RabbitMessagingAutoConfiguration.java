package com.stove.common.messaging.rabbitmq;

import com.stove.common.event.KeyQuarantine;
import com.stove.common.event.MessagePublisher;
import com.stove.common.event.OutboundMessage;
import com.stove.common.kafka.KafkaConsumerAutoConfiguration;
import com.stove.common.kafka.KafkaMessagePublisher;
import com.stove.common.messaging.MessagingAutoConfiguration;
import com.stove.common.messaging.config.RabbitMessagingProperties;
import com.stove.common.messaging.delivery.InPlaceDelivery;
import com.stove.common.messaging.delivery.SubscribedHandler;
import com.stove.common.messaging.trace.MicrometerReplayTracing;
import com.stove.common.messaging.trace.ReplayTracing;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * RabbitMQ adapter(결정 203)를 켠다: {@code stove.messaging.broker=rabbitmq}.
 * 연결·{@link AmqpAdmin} 은 스프링 부트 {@code spring.rabbitmq.*} 가 만들고,
 * publisher confirm·returns 는 {@link MessagingBrokerEnvironment} 가 켠다. Kafka 의 발행 포트 기본 빈보다 먼저 오고, Kafka 구독 등록은 같은 설정으로 꺼진다.
 */
@AutoConfiguration(after = {MessagingAutoConfiguration.class, RabbitAutoConfiguration.class},
        before = KafkaConsumerAutoConfiguration.class)
@ConditionalOnProperty(prefix = "stove.messaging", name = "broker", havingValue = "rabbitmq")
@EnableConfigurationProperties(RabbitMessagingProperties.class)
public class RabbitMessagingAutoConfiguration {

    @Bean
    public MessagePublisher rabbitMessagePublisher(ConnectionFactory connectionFactory, AmqpAdmin admin,
                                                   RabbitMessagingProperties properties,
                                                   ObjectProvider<KafkaTemplate<String, String>> kafkaTemplate) {
        RabbitMessagePublisher rabbit = new RabbitMessagePublisher(connectionFactory, admin, properties.queuesPerTopic(),
                properties.optionalTopics());
        return alsoKafka(rabbit, properties.alsoKafkaTopics(), kafkaTemplate.getIfAvailable());
    }

    /** Kafka 로도 보낼 토픽은 두 곳 모두 성공해야 성공이다. 그 토픽이 있는데 {@link KafkaTemplate} 이 없으면 기동을 멈춘다. */
    static MessagePublisher alsoKafka(MessagePublisher primary, Set<String> alsoKafkaTopics,
                                      KafkaTemplate<String, String> template) {
        if (alsoKafkaTopics.isEmpty()) {
            return primary;
        }
        if (template == null) {
            throw new IllegalStateException("stove.messaging.rabbitmq.also-kafka-topics=" + alsoKafkaTopics
                    + " 인데 KafkaTemplate 이 없다");
        }
        KafkaMessagePublisher kafka = new KafkaMessagePublisher(template);
        return (OutboundMessage message) -> alsoKafkaTopics.contains(message.topic())
                ? CompletableFuture.allOf(primary.publish(message), kafka.publish(message))
                : primary.publish(message);
    }

    @Bean
    public RabbitSubscriptions rabbitSubscriptions(ApplicationContext context, ConnectionFactory connectionFactory,
                                                   AmqpAdmin admin, KeyQuarantine quarantine,
                                                   RabbitMessagingProperties properties,
                                                   ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        Tracer availableTracer = tracer.getIfAvailable();
        Propagator availablePropagator = propagator.getIfAvailable();
        ReplayTracing tracing = availableTracer == null || availablePropagator == null
                ? ReplayTracing.NONE : new MicrometerReplayTracing(availableTracer, availablePropagator);
        return new RabbitSubscriptions(() -> SubscribedHandler.discover(context), connectionFactory, admin,
                new InPlaceDelivery(quarantine, tracing, InPlaceDelivery.RETRY_DELAYS),
                properties.queuesPerTopic(), properties.prefetch());
    }
}
