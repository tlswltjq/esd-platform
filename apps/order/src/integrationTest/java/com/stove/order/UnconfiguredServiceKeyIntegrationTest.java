package com.stove.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"stove.order.payment-service-key=", "server.address=127.0.0.1"})
class UnconfiguredServiceKeyIntegrationTest extends OrderIntegrationTestBase {
    @Autowired TestRestTemplate http;

    @Test
    void blankServerKeyDoesNotEnableEitherAdmissionEndpoint() {
        String path = "/internal/v1/orders/" + UUID.randomUUID() + "/payment-admissions";
        for (String endpoint : new String[]{path, path + "/" + UUID.randomUUID() + "/failure"}) {
            for (String value : new String[]{"", "arbitrary-credential"}) {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("X-Payment-Service-Key", value);
                assertThat(http.exchange(endpoint, HttpMethod.POST, new HttpEntity<>(Map.of(), headers), String.class)
                        .getStatusCode().value()).isEqualTo(401);
            }
        }
    }
}
