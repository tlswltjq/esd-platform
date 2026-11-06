package com.stove.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.catalog.api.listener.ReleaseSubmissionMapper;
import com.stove.catalog.core.service.PublishProjectRelease;
import com.stove.common.kafka.contract.payload.ProjectReleaseRequestedEvent;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "server.address=127.0.0.1")
class ServiceKeyBoundaryIntegrationTest extends CatalogIntegrationTestBase {
    private static final Map<String, String> KEYS = Map.of(
            "X-Store-Service-Key", newKey(), "X-Order-Service-Key", newKey(),
            "X-Download-Service-Key", newKey(), "X-Settlement-Service-Key", newKey());
    @Autowired TestRestTemplate http;
    @Autowired PublishProjectRelease publish;
    private String code;

    record Endpoint(String path, String header) { }

    static Stream<Endpoint> endpoints() {
        return Stream.of(new Endpoint("/internal/v1/store-products", "X-Store-Service-Key"),
                new Endpoint("/internal/v1/store-products/{code}", "X-Store-Service-Key"),
                new Endpoint("/internal/v1/order-products/{code}", "X-Order-Service-Key"),
                new Endpoint("/internal/v1/products/{code}/build", "X-Download-Service-Key"),
                new Endpoint("/internal/v1/products/{code}/owner", "X-Settlement-Service-Key"));
    }

    private static String newKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("stove.catalog.store-service-key", () -> KEYS.get("X-Store-Service-Key"));
        registry.add("stove.catalog.order-service-key", () -> KEYS.get("X-Order-Service-Key"));
        registry.add("stove.catalog.download-service-key", () -> KEYS.get("X-Download-Service-Key"));
        registry.add("stove.catalog.settlement-service-key", () -> KEYS.get("X-Settlement-Service-Key"));
    }

    @BeforeEach
    void publication() {
        ProjectReleaseRequestedEvent event = CatalogFixtures.event(CatalogFixtures.id(), 1);
        publish.execute(ReleaseSubmissionMapper.from(CatalogFixtures.envelope(event), event));
        code = event.partitionKey();
    }

    private ResponseEntity<String> call(Endpoint endpoint, HttpMethod method, Map<String, String> supplied) {
        HttpHeaders headers = new HttpHeaders();
        supplied.forEach(headers::set);
        ResponseEntity<String> response = http.exchange(endpoint.path().replace("{code}", code), method,
                new HttpEntity<Void>(headers), String.class);
        String output = String.valueOf(response.getBody()) + response.getHeaders();
        assertThat(KEYS.values().stream().noneMatch(output::contains))
                .as("Response must not expose any service credential").isTrue();
        return response;
    }

    @ParameterizedTest
    @MethodSource("endpoints")
    void correctKeyReachesPublishedDataWithNoStore(Endpoint endpoint) {
        ResponseEntity<String> response = call(endpoint, HttpMethod.GET,
                Map.of(endpoint.header(), KEYS.get(endpoint.header())));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getBody()).contains(code);
    }

    @ParameterizedTest
    @MethodSource("endpoints")
    void missingWrongAndCrossedCredentialsAreRejected(Endpoint endpoint) {
        assertThat(call(endpoint, HttpMethod.GET, Map.of()).getStatusCode().value()).isEqualTo(401);
        assertThat(call(endpoint, HttpMethod.GET, Map.of(endpoint.header(), "")).getStatusCode().value()).isEqualTo(401);
        assertThat(call(endpoint, HttpMethod.GET, Map.of(endpoint.header(), "wrong-credential"))
                .getStatusCode().value()).isEqualTo(401);
        for (Map.Entry<String, String> other : KEYS.entrySet()) {
            if (other.getKey().equals(endpoint.header())) continue;
            assertThat(call(endpoint, HttpMethod.GET, Map.of(endpoint.header(), other.getValue()))
                    .getStatusCode().value()).isEqualTo(401);
            assertThat(call(endpoint, HttpMethod.GET, Map.of(other.getKey(), other.getValue()))
                    .getStatusCode().value()).isEqualTo(401);
            assertThat(call(endpoint, HttpMethod.GET, Map.of(other.getKey(), KEYS.get(endpoint.header())))
                    .getStatusCode().value()).isEqualTo(401);
        }
    }

    @ParameterizedTest
    @MethodSource("endpoints")
    void correctKeyDoesNotPermitOtherMethodsOrInternalPaths(Endpoint endpoint) {
        Map<String, String> credential = Map.of(endpoint.header(), KEYS.get(endpoint.header()));
        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            assertThat(call(endpoint, method, credential).getStatusCode().value()).isEqualTo(401);
        }
        assertThat(call(new Endpoint("/internal/v1/not-exposed", endpoint.header()), HttpMethod.GET, credential)
                .getStatusCode().value()).isEqualTo(401);
        assertThat(call(new Endpoint("/api/v1/admin/catalog-release-jobs", endpoint.header()), HttpMethod.GET, credential)
                .getStatusCode().value()).isEqualTo(401);
    }

    @AfterEach
    void credentialsDoNotAppearInCapturedApplicationLogs(CapturedOutput output) {
        assertThat(KEYS.values().stream().noneMatch(output.getAll()::contains))
                .as("Application logs must not expose any service credential").isTrue();
    }
}
