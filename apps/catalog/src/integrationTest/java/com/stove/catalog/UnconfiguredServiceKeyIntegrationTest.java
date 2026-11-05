package com.stove.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"stove.catalog.store-service-key=", "stove.catalog.order-service-key=",
        "stove.catalog.download-service-key=", "stove.catalog.settlement-service-key=", "server.address=127.0.0.1"})
class UnconfiguredServiceKeyIntegrationTest extends CatalogIntegrationTestBase {
    @Autowired TestRestTemplate http;

    @Test
    void blankServerConfigurationNeverEnablesInternalAccess() {
        Map<String, String> endpoints = Map.of("/internal/v1/store-products", "X-Store-Service-Key",
                "/internal/v1/order-products/game", "X-Order-Service-Key",
                "/internal/v1/products/game/build", "X-Download-Service-Key",
                "/internal/v1/products/game/owner", "X-Settlement-Service-Key");
        for (Map.Entry<String, String> endpoint : endpoints.entrySet()) {
            for (String value : new String[]{"", "arbitrary-credential"}) {
                HttpHeaders headers = new HttpHeaders();
                headers.set(endpoint.getValue(), value);
                assertThat(http.exchange(endpoint.getKey(), HttpMethod.GET, new HttpEntity<Void>(headers), String.class)
                        .getStatusCode().value()).isEqualTo(401);
            }
        }
    }
}
