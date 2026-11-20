package com.stove.common.messaging.kafka;

import com.stove.common.event.MessagePublisher;
import com.stove.common.event.MirrorPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
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
     * Outbox 릴레이가 쓰는 발행 포트의 Kafka 구현. 다른 adapter 와 같이 {@code stove.messaging.broker} 로 고른다(결정 209) —
     * 자동 구성 순서에 기대 다른 adapter 가 먼저 빈을 만들어 주기를 바라지 않는다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "stove.messaging", name = "broker", havingValue = "kafka", matchIfMissing = true)
    @ConditionalOnMissingBean(MessagePublisher.class)
    public MessagePublisher stoveKafkaMessagePublisher(KafkaTemplate<String, String> kafkaTemplate) {
        return new KafkaMessagePublisher(kafkaTemplate);
    }

    /**
     * 다른 브로커를 고른 앱이 Kafka 로만 받는 소비자(DB 없는 Store)를 위해 일부 토픽을 Kafka 로도 보내는 미러 발행기.
     * 쓸지는 그 adapter 의 {@code also-kafka-topics} 가 정한다. 비어 있으면 아무도 부르지 않는다.
     */
    @Bean
    @ConditionalOnMissingBean(MirrorPublisher.class)
    public MirrorPublisher stoveKafkaMirrorPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        KafkaMessagePublisher kafka = new KafkaMessagePublisher(kafkaTemplate);
        return kafka::publish;
    }

    /** DLT 유입 계측. <b>지표 수집 여부가 실패 처리 가능 여부를 좌우해서는 안 된다.</b> */
    @Bean
    @ConditionalOnMissingBean
    public DeadLetterMetrics deadLetterMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
        return new DeadLetterMetrics(meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }
}
