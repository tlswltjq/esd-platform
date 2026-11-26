package com.stove.order.config;

import com.stove.common.messaging.kafka.ListenerContainerFactories;
import com.stove.order.core.domain.RefundCompletionRejected;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

/**
 * 환불 완료 근거 리스너의 컨테이너 팩토리. 계약을 어긴 근거({@link RefundCompletionRejected})는 재시도해도 결과가 같아
 * 첫 실패에 격리한다. 그 밖의 실패는 공용 정책(1·2·4초 재시도 뒤 소비 모듈 DB 격리)과 같다.
 */
@Configuration(proxyBeanMethods = false)
public class RefundKafkaConfiguration {

    /** {@code @KafkaListener(containerFactory = ...)} 로 고르는 빈 이름. */
    public static final String REFUND_COMPLETION_FACTORY = "orderRefundListenerContainerFactory";

    @Bean(REFUND_COMPLETION_FACTORY)
    ConcurrentKafkaListenerContainerFactory<Object, Object> orderRefundListenerContainerFactory(
            ListenerContainerFactories factories) {
        return factories.noRetryFor(RefundCompletionRejected.class);
    }
}
