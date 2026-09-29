package com.stove.store.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.store.core.domain.StoreProductView;
import java.time.Duration;
import java.util.List;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var type = mapper.getTypeFactory().constructCollectionType(List.class, StoreProductView.class);
        var values = new Jackson2JsonRedisSerializer<List<StoreProductView>>(mapper, type);
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(1))   // 진열은 신선도가 중요해 TTL 을 짧게
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        values));

        return RedisCacheManager.builder(connectionFactory).cacheDefaults(config).build();
    }
}
