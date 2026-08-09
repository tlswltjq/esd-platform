package com.stove.common.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.inbox.ProcessedEventRepository;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.messaging.outbox.OutboxMetrics;
import com.stove.common.messaging.outbox.OutboxProperties;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.messaging.outbox.OutboxRelay;
import com.stove.common.messaging.trace.MicrometerTraceContextCapture;
import com.stove.common.messaging.trace.TraceContextCapture;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox/Inbox 인프라 자동 구성.
 * 각 서비스는 build.gradle 에 common:messaging 만 추가하고
 * Application 클래스에서 {@code com.stove.common.messaging} 를 엔티티/리포지토리 스캔 대상에 포함하면 된다.
 *
 * <p>컨슈머 실패 처리(재시도 정책·DLT)는 여기 없다 — {@code common:kafka} 가 가진다.
 * 그쪽은 Outbox 도 JPA 도 필요 없으므로, 저장소가 JPA 가 아닌 서비스(store·download)도 쓸 수 있다.
 */
@Slf4j
@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class MessagingAutoConfiguration {

    /**
     * 적재 시점에 추적 컨텍스트를 붙잡는 장치.
     *
     * <p>추적을 구성한 서비스({@code micrometer-tracing-bridge-*} 가 클래스패스에 있으면
     * {@code Tracer} 와 {@code Propagator} 가 자동 구성된다)에서만 실제 구현이 붙고,
     * 아니면 비활성 구현으로 떨어진다. <b>추적 설정 여부가 이벤트 발행 가능 여부를 좌우해서는 안 된다</b> —
     * {@link #outboxMetrics} 가 레지스트리 없이도 도는 것과 같은 판단이다.
     */
    @Bean
    @ConditionalOnMissingBean
    public TraceContextCapture traceContextCapture(ObjectProvider<Tracer> tracer,
                                                   ObjectProvider<Propagator> propagator) {
        Tracer availableTracer = tracer.getIfAvailable();
        Propagator availablePropagator = propagator.getIfAvailable();
        if (availableTracer == null || availablePropagator == null) {
            log.info("추적 컨텍스트 전파 없음 — Kafka 구간에서 트레이스가 새로 시작된다");
            return TraceContextCapture.DISABLED;
        }
        return new MicrometerTraceContextCapture(availableTracer, availablePropagator);
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxRecorder outboxRecorder(OutboxEventRepository repository, ObjectMapper objectMapper,
                                         TraceContextCapture traceContextCapture) {
        return new OutboxRecorder(repository, objectMapper, traceContextCapture);
    }

    /**
     * 릴레이 계측. 액추에이터가 없는 구성에서도 뜨도록 레지스트리가 없으면 로컬 것을 만든다 —
     * 지표 수집 여부가 이벤트 발행 가능 여부를 좌우해서는 안 된다.
     */
    @Bean
    @ConditionalOnMissingBean
    public OutboxMetrics outboxMetrics(ObjectProvider<MeterRegistry> meterRegistry,
                                       OutboxEventRepository repository) {
        return new OutboxMetrics(meterRegistry.getIfAvailable(SimpleMeterRegistry::new), repository);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "stove.outbox", name = "relay-enabled", havingValue = "true", matchIfMissing = true)
    public OutboxRelay outboxRelay(OutboxEventRepository repository,
                                   KafkaTemplate<String, String> kafkaTemplate,
                                   OutboxProperties properties,
                                   OutboxMetrics metrics,
                                   PlatformTransactionManager transactionManager) {
        return new OutboxRelay(repository, kafkaTemplate, properties, metrics,
                new TransactionTemplate(transactionManager));
    }

    @Bean
    @ConditionalOnMissingBean
    public ProcessedEventGuard processedEventGuard(ProcessedEventRepository repository) {
        return new ProcessedEventGuard(repository);
    }

}
