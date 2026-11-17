package com.stove.common.kafka;

import com.stove.common.event.KeyQuarantine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

/**
 * 업무 리스너({@code @EventSubscriber})를 Kafka 구독으로 등록한다. 리스너는 Kafka 를 모른다(결정 202).
 *
 * <p>{@link KafkaConsumerAutoConfiguration} 과 나눈 이유: 그쪽은 {@code KafkaTemplate} 이 있을 때만 켜진다.
 * 등록기까지 그 조건에 묶이면 소비만 하는 구성에서 구독이 조용히 빠진다. 여기서는 조건 없이 켜고,
 * 필요한 Kafka 설정이 정말 없으면 등록기가 기동을 멈춘다.
 */
@AutoConfiguration(after = {KafkaAutoConfiguration.class, KafkaConsumerAutoConfiguration.class})
public class KafkaSubscriptionAutoConfiguration {

    @Bean
    public KafkaEventSubscriptions stoveKafkaEventSubscriptions(
            ApplicationContext context,
            ObjectProvider<ConcurrentKafkaListenerContainerFactoryConfigurer> configurer,
            ObjectProvider<ConsumerFactory<Object, Object>> consumers,
            ObjectProvider<KafkaTemplate<String, String>> kafkaTemplate,
            ObjectProvider<DeadLetterMetrics> metrics,
            ObjectProvider<MeterRegistry> meterRegistry,
            ObjectProvider<KeyQuarantine> quarantine) {
        return new KafkaEventSubscriptions(context, configurer::getIfAvailable, consumers::getIfAvailable,
                () -> deadLetters(kafkaTemplate, metrics, meterRegistry), quarantine::getIfAvailable);
    }

    private static ConsumerRecordRecoverer deadLetters(ObjectProvider<KafkaTemplate<String, String>> kafkaTemplate,
                                                       ObjectProvider<DeadLetterMetrics> metrics,
                                                       ObjectProvider<MeterRegistry> meterRegistry) {
        KafkaTemplate<String, String> template = kafkaTemplate.getIfAvailable();
        if (template == null) {
            return null;
        }
        DeadLetterMetrics deadLetterMetrics = metrics.getIfAvailable(
                () -> new DeadLetterMetrics(meterRegistry.getIfAvailable(SimpleMeterRegistry::new)));
        return DeadLetterPublisher.to(template, deadLetterMetrics);
    }
}
