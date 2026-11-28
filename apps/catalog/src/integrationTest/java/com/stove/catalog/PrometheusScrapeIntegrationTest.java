package com.stove.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

@AutoConfigureObservability(tracing = false)
@TestPropertySource(properties = "server.address=127.0.0.1")
class PrometheusScrapeIntegrationTest extends CatalogIntegrationTestBase {
    @Autowired private TestRestTemplate http;

    @Test
    void anonymousScrapeExportsRuntimeAndDurableOutboxMetrics() {
        ResponseEntity<String> response = http.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("jvm_memory_used_bytes", "stove_outbox_pending",
                "stove_outbox_dead_total", "application=\"catalog\"");
    }

    @Test
    void requestMetricsCarryTheErrorCodeOfTheResponse() {
        // 결정 error-observability: 상태 코드만으로는 어떤 오류였는지 알 수 없어 응답의 오류 코드를 태그로 싣는다.
        assertThat(http.getForEntity("/api/v1/products?page=abc", String.class).getStatusCode().value()).isEqualTo(400);
        assertThat(http.getForEntity("/api/v1/products", String.class).getStatusCode().value()).isEqualTo(200);

        List<String> requests = http.getForEntity("/actuator/prometheus", String.class).getBody().lines()
                .filter(line -> line.startsWith("http_server_requests_seconds_count{")
                        && line.contains("uri=\"/api/v1/products\""))
                .toList();

        assertThat(requests).anySatisfy(line -> assertThat(line).contains("status=\"400\"", "error_code=\"INVALID_REQUEST\""));
        assertThat(requests).anySatisfy(line -> assertThat(line).contains("status=\"200\"", "error_code=\"none\""));
    }

    @Test
    void scrapePermissionDoesNotOpenOtherActuatorPathsOrWrites() {
        for (String path : List.of("/actuator/env", "/actuator/metrics", "/actuator/prometheus/extra")) {
            assertThat(http.getForEntity(path, String.class).getStatusCode().value()).isEqualTo(401);
        }
        assertThat(http.postForEntity("/actuator/prometheus", null, String.class)
                .getStatusCode().value()).isEqualTo(401);
    }
}
