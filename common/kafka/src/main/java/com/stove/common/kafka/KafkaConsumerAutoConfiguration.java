package com.stove.common.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * 컨슈머가 실패했을 때 <b>얼마나 버티고, 포기한 뒤 무엇을 하는가.</b>
 * 이 모듈을 의존하는 것만으로 두 기본값이 깔린다. docs/code-notes.md
 */
@Slf4j
@AutoConfiguration
public class KafkaConsumerAutoConfiguration {

    /**
     * 재시도가 소진된 레코드를 {@code <원본토픽>.DLT} 의 <b>같은 파티션</b>으로 보낸다.
     * 도메인 처리가 필요한 서비스는 자기 {@link CommonErrorHandler} 빈으로 대신한다.
     * docs/code-notes.md
     */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    public DefaultErrorHandler stoveKafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                                      DeadLetterMetrics metrics) {
        ConsumerRecordRecoverer toDeadLetterTopic = DeadLetterPublisher.to(kafkaTemplate, metrics);
        return new DefaultErrorHandler(
                (record, exception) -> {
                    log.error("재시도 소진 — DLT 로 보낸다 topic={} partition={} offset={} key={}",
                            record.topic(), record.partition(), record.offset(), record.key(), exception);
                    toDeadLetterTopic.accept(record, exception);
                },
                ConsumerRetryPolicy.backOff());
    }

    /** DLT 유입 계측. <b>지표 수집 여부가 실패 처리 가능 여부를 좌우해서는 안 된다.</b> */
    @Bean
    @ConditionalOnMissingBean
    public DeadLetterMetrics deadLetterMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
        return new DeadLetterMetrics(meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }
}
