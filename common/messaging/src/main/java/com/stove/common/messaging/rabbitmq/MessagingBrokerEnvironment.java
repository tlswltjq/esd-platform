package com.stove.common.messaging.rabbitmq;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * spring-rabbit 이 모든 소비 앱의 클래스패스에 있으므로, 고른 브로커에 맞춰 스프링 부트 RabbitMQ 기본값을 정한다.
 * 가장 낮은 우선순위로 넣어 앱 설정·환경 변수가 언제나 이긴다.
 *
 * <ul>
 *   <li>RabbitMQ 를 고르지 않았으면 RabbitMQ 상태 검사를 끈다 — 쓰지 않는 브로커 때문에 앱이 DOWN 으로 보이지 않게.</li>
 *   <li>골랐으면 publisher confirm 을 켠다 — 발행 adapter 가 브로커 ack 를 받아야 릴레이가 {@code SENT} 로 바꾼다.</li>
 * </ul>
 */
public class MessagingBrokerEnvironment implements EnvironmentPostProcessor {

    static final String SOURCE = "stoveMessagingBrokerDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean rabbit = "rabbitmq".equals(environment.getProperty("stove.messaging.broker"));
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("management.health.rabbit.enabled", Boolean.toString(rabbit));
        if (rabbit) {
            defaults.put("spring.rabbitmq.publisher-confirm-type", "correlated");
        }
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE, defaults));
    }
}
