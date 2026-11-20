package com.stove.common.messaging.redis;

import com.stove.common.event.KeyQuarantine;
import com.stove.common.event.MessagePublisher;
import com.stove.common.event.MirrorPublisher;
import com.stove.common.messaging.MessagingAutoConfiguration;
import com.stove.common.messaging.delivery.InPlaceDelivery;
import com.stove.common.messaging.delivery.MirroredTopics;
import com.stove.common.messaging.delivery.SubscribedHandler;
import com.stove.common.messaging.redis.config.RedisStreamsMessagingProperties;
import com.stove.common.messaging.trace.MicrometerReplayTracing;
import com.stove.common.messaging.trace.ReplayTracing;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Redis Streams adapter(결정 203)를 켠다: {@code stove.messaging.broker=redis-streams}.
 * 스프링 부트 Redis 자동 구성(spring-data-redis)은 쓰지 않고 Lettuce 클라이언트를 여기서만 만든다 —
 * 다른 브로커를 고른 앱에 Redis 연결·상태 검사가 생기지 않게.
 */
@AutoConfiguration(after = MessagingAutoConfiguration.class)
@ConditionalOnProperty(prefix = "stove.messaging", name = "broker", havingValue = "redis-streams")
@EnableConfigurationProperties(RedisStreamsMessagingProperties.class)
public class RedisStreamsMessagingAutoConfiguration {

    @Bean(destroyMethod = "shutdown")
    public RedisClient stoveStreamsRedisClient(RedisStreamsMessagingProperties properties) {
        return RedisClient.create(properties.uri());
    }

    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, String> stoveStreamsPublishConnection(RedisClient stoveStreamsRedisClient) {
        return stoveStreamsRedisClient.connect();
    }

    @Bean
    public MessagePublisher redisStreamsMessagePublisher(StatefulRedisConnection<String, String> stoveStreamsPublishConnection,
                                                         RedisStreamsMessagingProperties properties,
                                                         ObjectProvider<MirrorPublisher> mirror) {
        return MirroredTopics.wrap(new RedisStreamsPublisher(stoveStreamsPublishConnection, properties.streamsPerTopic()),
                properties.alsoKafkaTopics(), mirror.getIfAvailable(), "stove.messaging.redis-streams.also-kafka-topics");
    }

    @Bean
    public RedisStreamsSubscriptions redisStreamsSubscriptions(ApplicationContext context, RedisClient stoveStreamsRedisClient,
                                                               KeyQuarantine quarantine, RedisStreamsMessagingProperties properties,
                                                               ObjectProvider<Tracer> tracer,
                                                               ObjectProvider<Propagator> propagator) {
        Tracer availableTracer = tracer.getIfAvailable();
        Propagator availablePropagator = propagator.getIfAvailable();
        ReplayTracing tracing = availableTracer == null || availablePropagator == null
                ? ReplayTracing.NONE : new MicrometerReplayTracing(availableTracer, availablePropagator);
        return new RedisStreamsSubscriptions(() -> SubscribedHandler.discover(context), stoveStreamsRedisClient,
                new InPlaceDelivery(quarantine, tracing, InPlaceDelivery.RETRY_DELAYS),
                properties.streamsPerTopic(), properties.batchSize(), properties.blockMs());
    }
}
