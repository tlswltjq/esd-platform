package com.stove.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderRequest;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PublishedProduct;
import com.stove.order.core.port.CatalogProducts;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "server.address=127.0.0.1")
class ServiceKeyBoundaryIntegrationTest extends OrderIntegrationTestBase {
    private static final String KEY = newKey();
    private static final String OTHER_KEY = newKey();
    @Autowired TestRestTemplate http;
    @Autowired OrderRepository orders;
    @MockitoBean CatalogProducts catalog;
    private Order order;
    private String admissionId;
    private String paymentId;
    private Map<String, Object> request;

    private static String newKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @DynamicPropertySource
    static void key(DynamicPropertyRegistry registry) {
        registry.add("stove.order.payment-service-key", () -> KEY);
    }

    @BeforeEach
    void pendingOrder() {
        String suffix = UUID.randomUUID().toString();
        PublishedProduct product = new PublishedProduct(1L, "key-test", "Key test", 1, 10000, "KRW");
        order = orders.saveAndFlush(Order.create("buyer-" + suffix,
                new OrderRequest(suffix, product.productCode(), 1, 10000), product, UUID.randomUUID().toString()));
        when(catalog.detail(product.productCode())).thenReturn(product);
        admissionId = UUID.randomUUID().toString();
        paymentId = UUID.randomUUID().toString();
        request = Map.of("admissionId", admissionId, "requestEventId", order.getRequestEventId(),
                "paymentId", paymentId, "buyerSubject", order.getBuyerSubject(), "amount", 10000, "currency", "KRW");
    }

    private String path() { return "/internal/v1/orders/" + order.getOrderNo() + "/payment-admissions"; }

    private ResponseEntity<String> call(String path, HttpMethod method, Map<String, String> supplied, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        supplied.forEach(headers::set);
        ResponseEntity<String> response = http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
        String output = String.valueOf(response.getBody()) + response.getHeaders();
        assertThat(!output.contains(KEY) && !output.contains(OTHER_KEY))
                .as("Response must not expose service credentials").isTrue();
        return response;
    }

    @Test
    void correctPaymentKeyAdmitsAndRecordsVerifiedFailure() {
        Map<String, String> credential = Map.of("X-Payment-Service-Key", KEY);
        ResponseEntity<String> admitted = call(path(), HttpMethod.POST, credential, request);
        assertThat(admitted.getStatusCode().value()).isEqualTo(200);
        assertThat(admitted.getHeaders().getCacheControl()).contains("no-store");
        assertThat(orders.findByOrderNo(order.getOrderNo()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_PROCESSING);
        Map<String, Object> failure = Map.of("paymentId", paymentId, "supplierAttemptId", "key-check-attempt",
                "failedAt", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        ResponseEntity<String> failed = call(path() + "/" + admissionId + "/failure", HttpMethod.POST, credential, failure);
        assertThat(failed.getStatusCode().value()).isEqualTo(200);
        assertThat(failed.getHeaders().getCacheControl()).contains("no-store");
        assertThat(orders.findByOrderNo(order.getOrderNo()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void missingCrossedKeysAndUserJwtCannotAdmitOrReportFailure() {
        List<Map<String, String>> invalid = List.of(Map.of(), Map.of("X-Payment-Service-Key", ""),
                Map.of("X-Payment-Service-Key", "wrong-credential"), Map.of("X-Payment-Service-Key", OTHER_KEY),
                Map.of("X-Order-Service-Key", OTHER_KEY), Map.of("X-Order-Service-Key", KEY),
                Map.of("X-Store-Service-Key", KEY), Map.of("X-Download-Service-Key", KEY),
                Map.of("X-Settlement-Service-Key", KEY), Map.of("Authorization", "Bearer " + token("operator", "ADMIN")));
        for (String endpoint : List.of(path(), path() + "/" + admissionId + "/failure")) {
            for (Map<String, String> credential : invalid) {
                assertThat(call(endpoint, HttpMethod.POST, credential, request).getStatusCode().value()).isEqualTo(401);
            }
        }
        assertThat(orders.findByOrderNo(order.getOrderNo()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        verifyNoInteractions(catalog);
    }

    @Test
    void correctKeyIsLimitedToAdmissionPostPaths() {
        Map<String, String> credential = Map.of("X-Payment-Service-Key", KEY);
        for (String endpoint : List.of(path(), path() + "/" + admissionId + "/failure")) {
            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
                assertThat(call(endpoint, method, credential, request).getStatusCode().value()).isEqualTo(401);
            }
        }
        assertThat(call("/internal/v1/not-exposed", HttpMethod.POST, credential, request).getStatusCode().value()).isEqualTo(401);
        assertThat(call("/api/v1/orders", HttpMethod.GET, credential, null).getStatusCode().value()).isEqualTo(401);
        assertThat(orders.findByOrderNo(order.getOrderNo()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        verifyNoInteractions(catalog);
    }

    @AfterEach
    void credentialsAreAbsentFromCapturedLogs(CapturedOutput output) {
        assertThat(!output.getAll().contains(KEY) && !output.getAll().contains(OTHER_KEY))
                .as("Application logs must not expose service credentials").isTrue();
    }
}
