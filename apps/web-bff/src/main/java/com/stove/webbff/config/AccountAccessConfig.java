package com.stove.webbff.config;

import com.stove.webbff.adapter.metrics.MicrometerProcessingLag;
import com.stove.webbff.adapter.redis.RedisSubjectSessions;
import com.stove.webbff.core.port.ProcessingLag;
import com.stove.webbff.core.port.SubjectSessions;
import com.stove.webbff.core.service.EndSubjectSessions;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/** 즉시 차단의 웹 BFF 쪽(결정 180·225, GW-T18): 계정 접근 변경 사실로 그 subject의 세션 토큰 기록을 지운다. */
@Configuration
public class AccountAccessConfig {

    @Bean
    SubjectSessions subjectSessions(ReactiveStringRedisTemplate redis,
            @Value("${spring.data.redis.timeout:2s}") Duration timeout) {
        return new RedisSubjectSessions(redis, timeout.plusSeconds(1));
    }

    @Bean
    ProcessingLag accessChangeLag(MeterRegistry registry) {
        return new MicrometerProcessingLag(registry, EndSubjectSessions.CONSUMER_GROUP, Clock.systemUTC());
    }
}
