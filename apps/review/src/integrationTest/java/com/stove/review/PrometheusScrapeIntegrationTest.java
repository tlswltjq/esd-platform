package com.stove.review;

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
class PrometheusScrapeIntegrationTest extends ReviewIntegrationTestBase {
    @Autowired private TestRestTemplate http;

    @Test
    void anonymousScrapeExportsRuntimeAndDurableOutboxMetrics() {
        ResponseEntity<String> response = http.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .contains("jvm_memory_used_bytes", "stove_outbox_pending",
                        "stove_outbox_dead_total", "application=\"review\"");
    }

    @Test
    void scrapePermissionDoesNotOpenOtherActuatorPathsOrWrites() {
        for (String path : List.of("/actuator/env", "/actuator/prometheus/extra")) {
            assertThat(http.getForEntity(path, String.class).getStatusCode().value()).isEqualTo(401);
        }
        assertThat(http.postForEntity("/actuator/prometheus", null, String.class)
                .getStatusCode().value()).isEqualTo(401);
    }
}
