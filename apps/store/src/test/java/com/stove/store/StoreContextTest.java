package com.stove.store;

import com.stove.common.test.InfraContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 실제 인프라를 띄우고 애플리케이션 컨텍스트를 끝까지 올린다.
 *
 * <p>정적 검증이 놓치는 부류를 잡는 것이 목적이다 — 빈 이름 충돌, 순환 의존,
 * 누락된 빈, {@code @ConfigurationProperties} 바인딩 실패, Flyway 마이그레이션과
 * 엔티티 매핑의 불일치({@code ddl-auto: validate}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({InfraContainers.Elasticsearch.class, InfraContainers.Kafka.class, InfraContainers.Redis.class})
class StoreContextTest {

    @Test
    @DisplayName("애플리케이션 컨텍스트가 로드된다")
    void contextLoads() {
    }
}
