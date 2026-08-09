package com.stove.common.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.inbox.ProcessedEventRepository;
import com.stove.common.messaging.kafka.ConsumerRetryPolicy;
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
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox/Inbox 인프라 자동 구성.
 * 각 서비스는 build.gradle 에 common:messaging 만 추가하고
 * Application 클래스에서 {@code com.stove.common.messaging} 를 엔티티/리포지토리 스캔 대상에 포함하면 된다.
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

    /**
     * 컨슈머 재시도 정책의 기본값.
     *
     * <p>정책을 정하지 않으면 스프링 카프카 기본값({@code FixedBackOff(0ms, 9회)})이 쓰여
     * 재시도가 수 밀리초 만에 소진된다. 여기서 지수 백오프를 깔아 두면 서비스마다
     * 잊고 지나갈 일이 없다.
     *
     * <p>재시도가 소진된 레코드는 기록하고 건너뛴다 — 계약 위반 메시지 한 건이
     * 파티션 전체를 막지 않게 하기 위함이다. 유실이 아니라 <b>관측 가능한 포기</b>여야 하므로
     * ERROR 로 남긴다.
     *
     * <p>보상 트랜잭션처럼 도메인 처리가 필요한 서비스는 자기 {@code CommonErrorHandler} 빈을
     * 정의해 이 기본값을 대신한다(예: {@code license} 의 {@code KafkaErrorHandlerConfig}).
     */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    public DefaultErrorHandler stoveKafkaErrorHandler() {
        return new DefaultErrorHandler(
                (record, exception) -> log.error(
                        "재시도 소진 — 레코드를 건너뛴다 topic={} partition={} offset={} key={}",
                        record.topic(), record.partition(), record.offset(), record.key(), exception),
                ConsumerRetryPolicy.backOff());
    }
}
