package com.stove.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.messaging.outbox.OutboxRelay;
import com.stove.common.test.InfraContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

/**
 * 실제 인프라를 띄우고 애플리케이션 컨텍스트를 끝까지 올린다.
 *
 * <p>정적 검증이 놓치는 부류를 잡는 것이 목적이다 — 빈 이름 충돌, 순환 의존,
 * 누락된 빈, {@code @ConfigurationProperties} 바인딩 실패, Flyway 마이그레이션과
 * 엔티티 매핑의 불일치({@code ddl-auto: validate}).
 *
 * <p><b>릴레이는 켜 두되 폴링은 재우다.</b> 여기는 전체 구성을 확인하는 자리라
 * {@code relay-enabled} 를 끄면 {@code @ConditionalOnProperty} 때문에 빈 자체가 사라져
 * 검증이 헐거워진다. 그런데 스프링이 이 컨텍스트를 캐시하므로 릴레이 스레드는
 * <b>테스트 JVM 이 끝날 때까지 살아서</b> 1초마다 같은 DB 를 훑는다 —
 * 릴레이를 끈 다른 테스트가 만든 이벤트를 집어가 버린다.
 *
 * <p>그래서 폴링 주기를 1시간으로 준다. 빈은 그대로 있어 구성 검증은 유지되고,
 * 실제 폴링은 컨텍스트 기동 직후 1회로 끝나 다른 테스트와 겹치지 않는다.
 * 단언을 느슨하게 푸는 대신 경합 자체를 없애는 쪽이다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "stove.outbox.poll-interval-ms=3600000")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class PaymentContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("애플리케이션 컨텍스트가 로드된다")
    void contextLoads() {
    }

    @Test
    @DisplayName("릴레이 빈이 실제로 구성된다 — 폴링을 재웠다고 검증까지 헐거워지면 안 된다")
    void outboxRelayIsWired() {
        assertThat(context.getBeanNamesForType(OutboxRelay.class))
                .as("@ConditionalOnProperty 기본값(matchIfMissing=true)으로 떠야 한다")
                .hasSize(1);
    }
}
