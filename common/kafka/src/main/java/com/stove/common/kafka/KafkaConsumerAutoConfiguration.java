package com.stove.common.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * 컨슈머가 실패했을 때 <b>얼마나 버티고, 포기한 뒤 무엇을 하는가.</b>
 * 이 모듈을 의존하는 것만으로 두 기본값이 깔린다.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnBean(KafkaTemplate.class)
public class KafkaConsumerAutoConfiguration {

    /**
     * 재시도가 소진된 레코드를 {@code <원본토픽>.DLT} 의 <b>같은 파티션</b>으로 보낸다.
     * 도메인 처리가 필요한 서비스는 자기 {@link CommonErrorHandler} 빈으로 대신한다.
     *
     */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    public DefaultErrorHandler stoveKafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                                      DeadLetterMetrics metrics) {
        return DeadLetterErrorHandlers.create(DeadLetterPublisher.to(kafkaTemplate, metrics));
    }

    /**
     * 업무 리스너({@code @EventSubscriber})를 Kafka 구독으로 등록한다. 리스너는 Kafka 를 모른다(결정 202).
     */
    @Bean
    public KafkaEventSubscriptions stoveKafkaEventSubscriptions(
            ListableBeanFactory beans, ObjectProvider<ConcurrentKafkaListenerContainerFactoryConfigurer> configurer,
            ObjectProvider<ConsumerFactory<Object, Object>> consumers, KafkaTemplate<String, String> kafkaTemplate,
            DeadLetterMetrics metrics) {
        ConsumerRecordRecoverer deadLetters = DeadLetterPublisher.to(kafkaTemplate, metrics);
        // 조건부 빈으로 두지 않는다. 조건이 빗나가면 리스너가 조용히 아무것도 받지 않는다 — 없으면 등록 시점에 실패시킨다.
        return new KafkaEventSubscriptions(beans, configurer::getIfAvailable, consumers::getIfAvailable, deadLetters);
    }

    /** DLT 유입 계측. <b>지표 수집 여부가 실패 처리 가능 여부를 좌우해서는 안 된다.</b> */
    @Bean
    @ConditionalOnMissingBean
    public DeadLetterMetrics deadLetterMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
        return new DeadLetterMetrics(meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }
}
