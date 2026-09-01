package com.stove.license;

import com.stove.common.testcontainers.InfraContainers;
import com.stove.common.test.OpenApiSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/** 기동 검증(L5) — 빈 구성과 Flyway↔엔티티 정합. docs/testing.md */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 캐시된 컨텍스트의 릴레이 스레드가 다른 테스트의 Outbox 이벤트를 집어가지 않도록
                // 폴링을 재운다. 빈은 그대로 둬서 구성 검증은 유지한다.
                "stove.outbox.poll-interval-ms=3600000",
                // 같은 이유가 컨슈머 쪽에도 그대로 있다. 이 컨텍스트는 프로퍼티가 달라 캐시 키가
                // 갈리므로 다른 테스트 컨텍스트와 JVM 안에 함께 산다 — 리스너를 띄우면 그 둘이
                // 같은 그룹(license)의 두 멤버가 되고, 파티션은 그중 하나에게만 간다.
                // 그러면 다른 테스트가 자기 리스너를 멈춰도 여기 있는 멤버가 이어서 소비한다.
                // 빈과 엔드포인트 등록은 그대로라 배선 검증은 유지된다.
                "spring.kafka.listener.auto-startup=false"})
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class LicenseContextTest {

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("애플리케이션 컨텍스트가 로드된다")
    void contextLoads() {
    }

    @Test
    @DisplayName("API 명세가 커밋된 계약과 일치한다")
    void openApiMatchesSnapshot() {
        OpenApiSnapshot.verify(port, "license");
    }
}
