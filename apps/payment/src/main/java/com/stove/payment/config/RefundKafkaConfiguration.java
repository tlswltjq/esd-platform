package com.stove.payment.config;

import com.stove.common.messaging.kafka.ListenerContainerFactories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

/**
 * 환불 실행 요청 리스너의 컨테이너 팩토리. 한 건마다 공급자 HTTP 를 기다릴 수 있어 한 번에 한 건만 가져온다
 * — 여러 건을 한꺼번에 가져오면 뒤 건들이 {@code max.poll.interval.ms} 를 넘긴다.
 */
@Configuration(proxyBeanMethods = false)
public class RefundKafkaConfiguration {

    /** {@code @KafkaListener(containerFactory = ...)} 로 고르는 빈 이름. */
    public static final String REFUND_EXECUTION_FACTORY = "paymentRefundExecutionListenerContainerFactory";

    @Bean(REFUND_EXECUTION_FACTORY)
    ConcurrentKafkaListenerContainerFactory<Object, Object> paymentRefundExecutionListenerContainerFactory(
            ListenerContainerFactories factories) {
        return factories.oneAtATime();
    }
}
