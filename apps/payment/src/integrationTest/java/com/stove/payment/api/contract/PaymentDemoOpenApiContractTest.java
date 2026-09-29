package com.stove.payment.api.contract;

import com.stove.common.test.OpenApiSnapshot;
import com.stove.common.testcontainers.InfraContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** 데모 프로필에서만 노출되는 시뮬레이터 API도 별도 계약으로 고정한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "stove.outbox.poll-interval-ms=3600000")
@ActiveProfiles("test")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class PaymentDemoOpenApiContractTest {

    @LocalServerPort private int port;

    @Test
    @DisplayName("데모 프로필의 결제 API 명세는 커밋된 계약과 일치한다")
    void demoOpenApiMatchesSnapshot() {
        OpenApiSnapshot.verify(port, "payment-demo");
    }
}
