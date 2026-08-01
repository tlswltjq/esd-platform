package com.stove.common.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.inbox.ProcessedEventRepository;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.messaging.outbox.OutboxProperties;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.messaging.outbox.OutboxRelay;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Outbox/Inbox 인프라 자동 구성.
 * 각 서비스는 build.gradle 에 common:messaging 만 추가하고
 * Application 클래스에서 {@code com.stove.common.messaging} 를 엔티티/리포지토리 스캔 대상에 포함하면 된다.
 */
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
}
