package com.stove.order.config;

import com.stove.common.kafka.ConsumerRetryPolicy;
import com.stove.common.kafka.DeadLetterMetrics;
import com.stove.common.kafka.DeadLetterPublisher;
import com.stove.order.core.domain.RefundCompletionRejected;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

@Slf4j
@Configuration(proxyBeanMethods = false)
public class RefundKafkaConfiguration {
    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> orderRefundListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumers,
            KafkaTemplate<String, String> kafka, DeadLetterMetrics metrics) {
        // Boot configurer의 고정 제네릭 시그니처다. 업무 입력을 Object로 넓혀 전달하지 않는다.
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumers);
        ConsumerRecordRecoverer publisher = DeadLetterPublisher.to(kafka, metrics);
        DefaultErrorHandler handler = new DefaultErrorHandler((record, exception) -> {
            log.error("환불 완료 근거 처리 격리 topic={} partition={} offset={} key={}",
                    record.topic(), record.partition(), record.offset(), record.key(), exception);
            publisher.accept(record, exception);
        }, ConsumerRetryPolicy.backOff());
        handler.addNotRetryableExceptions(RefundCompletionRejected.class);
        factory.setCommonErrorHandler(handler);
        return factory;
    }
}
