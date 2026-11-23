package com.stove.common.messaging.kafka;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Kafka 리스너 동시성 기본값을 토픽 파티션 수로 둔다(결정 212). 가장 낮은 우선순위로 넣어 앱 설정·환경 변수가 언제나 이긴다.
 *
 * <p>Spring Boot 기본값(1)이면 소비 그룹마다 스레드 하나가 파티션을 모두 맡아, 한 키의 제자리 재시도(1·2·4초)가
 * 다른 파티션의 키까지 막는다(E12 S5: 다른 파티션 7,682ms). 파티션마다 스레드 하나면 같은 키 순서(결정 202)는 그대로이고
 * 막힘은 그 파티션 안으로 한정된다 — 다른 adapter 가 분할마다 소비자를 두는 것과 같다.
 *
 * <p>Boot 의 컨테이너 팩토리 설정({@code ConcurrentKafkaListenerContainerFactoryConfigurer})이 이 값을 읽으므로 기본 팩토리와
 * 구독 전용 팩토리({@link KafkaEventSubscriptions#factoryFor}) 모두에 걸린다.
 */
public class KafkaListenerDefaults implements EnvironmentPostProcessor {

    /**
     * 토픽 파티션 수. 아직 코드에 선언하지 않고 브로커 자동 생성 값({@code docker-compose.yml} 의 3)에 기댄다 —
     * 둘이 어긋나지 않게 플랫폼 T11(토픽·파티션 선언)에서 함께 다룬다.
     */
    static final int PARTITIONS = 3;

    static final String SOURCE = "stoveKafkaListenerDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE,
                Map.of("spring.kafka.listener.concurrency", Integer.toString(PARTITIONS))));
    }
}
