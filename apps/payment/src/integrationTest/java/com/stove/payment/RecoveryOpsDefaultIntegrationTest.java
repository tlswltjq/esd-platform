package com.stove.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

/** 기본 설정에서는 ADMIN에게도 공통 운영 API가 존재하지 않는다. */
@TestPropertySource(properties = {
        "stove.payment.pg.provider=SIMULATED", "stove.payment.pg.base-url=http://127.0.0.1:1",
        "stove.payment.pg.api-key=ops-fixture-key", "stove.payment.pg.connect-timeout=PT1S",
        "stove.payment.pg.read-timeout=PT1S"})
class RecoveryOpsDefaultIntegrationTest extends PaymentDatabaseIntegrationTestBase {
    @LocalServerPort int port;

    @Test
    void opsApiIsAbsentUntilAnInstanceExplicitlyEnablesIt() {
        for (String path : List.of("/api/v1/ops/outbox/dead", "/api/v1/ops/dlt?topic=stove.order.v1.DLT")) {
            int status = RestClient.create("http://localhost:" + port).method(HttpMethod.GET).uri(path)
                    .header("Authorization", "Bearer " + token("operator", "ADMIN"))
                    .exchange((request, response) -> response.getStatusCode().value());
            assertThat(status).isEqualTo(404);
        }
    }
}
