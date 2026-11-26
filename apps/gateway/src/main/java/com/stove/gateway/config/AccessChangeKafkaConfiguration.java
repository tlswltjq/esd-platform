package com.stove.gateway.config;

import com.stove.common.messaging.kafka.ListenerContainerFactories;
import com.stove.gateway.core.domain.AccessChangeRejected;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

/**
 * 계정 접근 변경 리스너의 컨테이너 팩토리(결정 226). 일시 실패는 성공할 때까지 제자리 재시도하고(차단이 빠지면 안 되고,
 * 멱등·순서 무관이라 다시 처리해도 같다), 계약 위반({@link AccessChangeRejected})만 바로 DLT 로 보낸다.
 */
@Configuration(proxyBeanMethods = false)
public class AccessChangeKafkaConfiguration {

    /** {@code @KafkaListener(containerFactory = ...)} 로 고르는 빈 이름. */
    public static final String ACCESS_CHANGE_FACTORY = "accessChangeListenerContainerFactory";

    @Bean(ACCESS_CHANGE_FACTORY)
    ConcurrentKafkaListenerContainerFactory<Object, Object> accessChangeListenerContainerFactory(
            ListenerContainerFactories factories) {
        return factories.retryUntilSuccess(AccessChangeRejected.class);
    }
}
