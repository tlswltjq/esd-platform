package com.stove.gateway.config;

import com.stove.gateway.adapter.metrics.MicrometerProcessingLag;
import com.stove.gateway.adapter.redis.RedisAccessBaselines;
import com.stove.gateway.core.port.ProcessingLag;
import com.stove.gateway.core.service.RecordAccessChange;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * 즉시 차단의 게이트웨이 쪽(결정 180·225, GW-T18): 계정 접근 변경 사실로 subject별 거절 기준을 두고 이전 토큰을 거절한다.
 *
 * <ul>
 *   <li>{@code stove.gateway.access-baseline.ttl}: 거절 기준·멱등 키 수명. access token 최대 수명(Auth
 *       {@code stove.auth.bff-access-token-seconds}, 기본 15분) 이상이어야 한다.
 *   <li>{@code stove.gateway.access-baseline.clock-skew}: Auth 인스턴스 간 시계 오차 여유(Auth design 시계 전제, PR #244 R3).
 * </ul>
 */
@Configuration
public class AccessRevocationConfig {

    @Bean
    RedisAccessBaselines accessBaselines(ReactiveStringRedisTemplate redis,
            @Value("${stove.gateway.access-baseline.ttl:20m}") Duration ttl,
            @Value("${spring.data.redis.timeout:2s}") Duration timeout) {
        return new RedisAccessBaselines(redis, ttl, timeout.plusSeconds(1));
    }

    @Bean
    ProcessingLag accessChangeLag(MeterRegistry registry) {
        return new MicrometerProcessingLag(registry, RecordAccessChange.CONSUMER_GROUP, Clock.systemUTC());
    }

    @Bean
    BaselineStoreUnavailableHandler baselineStoreUnavailableHandler() {
        return new BaselineStoreUnavailableHandler();
    }
}
