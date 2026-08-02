package com.stove.common.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.inbox.ProcessedEventRepository;
import com.stove.common.messaging.kafka.ConsumerRetryPolicy;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.messaging.outbox.OutboxProperties;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.messaging.outbox.OutboxRelay;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;

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

    @Bean
    @ConditionalOnMissingBean
    public OutboxRecorder outboxRecorder(OutboxEventRepository repository, ObjectMapper objectMapper) {
        return new OutboxRecorder(repository, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "stove.outbox", name = "relay-enabled", havingValue = "true", matchIfMissing = true)
    public OutboxRelay outboxRelay(OutboxEventRepository repository,
                                   KafkaTemplate<String, String> kafkaTemplate,
                                   OutboxProperties properties) {
        return new OutboxRelay(repository, kafkaTemplate, properties);
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
