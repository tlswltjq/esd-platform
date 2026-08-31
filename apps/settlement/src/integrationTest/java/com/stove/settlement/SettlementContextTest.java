package com.stove.settlement;

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
        // 폴링을 재운다 — 캐시된 컨텍스트의 릴레이 경합(docs/testing.md).
        properties = "stove.outbox.poll-interval-ms=3600000")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class SettlementContextTest {

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("애플리케이션 컨텍스트가 로드된다")
    void contextLoads() {
    }

    @Test
    @DisplayName("API 명세가 커밋된 계약과 일치한다")
    void openApiMatchesSnapshot() {
        OpenApiSnapshot.verify(port, "settlement");
    }
}
