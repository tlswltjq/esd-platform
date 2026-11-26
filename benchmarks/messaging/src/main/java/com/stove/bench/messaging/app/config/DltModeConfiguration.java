package com.stove.bench.messaging.app.config;

import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.inbox.ProcessedEventRepository;
import com.stove.common.messaging.kafka.KeyQuarantine;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 격리처를 DLT로 바꾼 계열(E13 시나리오 4의 DLT 경로, 프로필 {@code dlt}). {@code application-dlt.yml}이 {@code common:messaging}의
 * 자동 구성을 빼서 {@link KeyQuarantine} 빈이 없게 하고 — 그러면 {@code common:messaging-kafka}가 DB 없는 앱(Store 등)처럼
 * {@code <토픽>.DLT}로 보낸다 — Inbox 가드만 여기서 같은 클래스로 다시 세운다. 격리처 밖의 처리는 기본 계열과 같다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "stove.bench", name = "recovery", havingValue = "dlt")
public class DltModeConfiguration {

    @Bean
    ProcessedEventGuard processedEventGuard(ProcessedEventRepository repository, MeterRegistry meterRegistry,
                                            ObjectProvider<KeyQuarantine> quarantine) {
        // 자동 구성 제외를 빠뜨리면 격리 표로 가면서 DLT 계열이라고 기록된다. 그런 회차는 띄우지 않는다.
        if (quarantine.getIfAvailable() != null) {
            throw new IllegalStateException("DLT 계열인데 격리 표(KeyQuarantine)가 켜져 있다 — application-dlt.yml 의 자동 구성 제외를 확인할 것");
        }
        return new ProcessedEventGuard(repository, meterRegistry);
    }
}
