package com.stove.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.messaging.outbox.OutboxRelay;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.common.test.OpenApiSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

/** 기동 검증(L5) — 빈 구성과 Flyway↔엔티티 정합. docs/testing.md */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "stove.outbox.poll-interval-ms=3600000")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class PaymentContextTest {

    @Autowired
    private ApplicationContext context;

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("애플리케이션 컨텍스트가 로드된다")
    void contextLoads() {
    }

    @Test
    @DisplayName("API 명세가 커밋된 계약과 일치한다")
    void openApiMatchesSnapshot() {
        OpenApiSnapshot.verify(port, "payment");
    }

    @Test
    @DisplayName("릴레이 빈이 실제로 구성된다 — 폴링을 재웠다고 검증까지 헐거워지면 안 된다")
    void outboxRelayIsWired() {
        assertThat(context.getBeanNamesForType(OutboxRelay.class))
                .as("@ConditionalOnProperty 기본값(matchIfMissing=true)으로 떠야 한다")
                .hasSize(1);
    }
}
