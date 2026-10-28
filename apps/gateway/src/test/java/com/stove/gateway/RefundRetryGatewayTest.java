package com.stove.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RefundRetryGatewayTest {
    private static final String ROOT = "/api/v1/orders/11111111-1111-1111-1111-111111111111/refund";
    private static final String BODY = "{\"previousAttemptId\":\"22222222-2222-2222-2222-222222222222\"}";
    private static final HttpServer ORDER;
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicReference<String> MEMBER_HEADER = new AtomicReference<>();
    private static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();
    private static final AtomicReference<String> REQUEST_BODY = new AtomicReference<>();
    private static final AtomicReference<String> REQUEST_PATH = new AtomicReference<>();
    static {
        try {
            ORDER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ORDER.createContext(ROOT, exchange -> {
                CALLS.incrementAndGet(); MEMBER_HEADER.set(exchange.getRequestHeaders().getFirst("X-Member-Id"));
                AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                REQUEST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                REQUEST_PATH.set(exchange.getRequestURI().getPath());
                byte[] body = "{\"success\":true,\"data\":{\"previousAttemptId\":\"22222222-2222-2222-2222-222222222222\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("Location", ROOT);
                exchange.sendResponseHeaders(202, body.length); exchange.getResponseBody().write(body); exchange.close();
            });
            ORDER.start(); Runtime.getRuntime().addShutdownHook(new Thread(() -> ORDER.stop(0)));
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    @Autowired WebTestClient http;
    @MockitoBean ReactiveJwtDecoder decoder;
    @DynamicPropertySource static void order(DynamicPropertyRegistry properties) {
        properties.add("ORDER_URI", () -> "http://127.0.0.1:" + ORDER.getAddress().getPort());
    }
    @BeforeEach void tokens() {
        CALLS.set(0); MEMBER_HEADER.set(null); AUTHORIZATION.set(null); REQUEST_BODY.set(null); REQUEST_PATH.set(null);
        when(decoder.decode(anyString())).thenAnswer(call -> {
            String role = call.getArgument(0, String.class);
            return Mono.just(Jwt.withTokenValue(role).header("alg", "RS256").subject("buyer")
                    .claim("roles", List.of(role)).build());
        });
    }
    @Test void memberRetryPreservesThePathBodyBearerReceiptAndPollingLocation() {
        http.post().uri(ROOT + "/retries").header("Authorization", "Bearer MEMBER").header("X-Member-Id", "forged")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(BODY).exchange().expectStatus().isAccepted()
                .expectHeader().valueEquals("Location", ROOT).expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.data.previousAttemptId").isEqualTo("22222222-2222-2222-2222-222222222222");
        assertThat(CALLS.get()).isEqualTo(1); assertThat(MEMBER_HEADER.get()).isNull();
        assertThat(AUTHORIZATION.get()).isEqualTo("Bearer MEMBER"); assertThat(REQUEST_BODY.get()).isEqualTo(BODY);
        assertThat(REQUEST_PATH.get()).isEqualTo(ROOT + "/retries");
    }
    @Test void anonymousAndNonMembersCannotForwardARetry() {
        // An anonymous browser write is rejected by CSRF before authentication; nothing reaches Order.
        http.post().uri(ROOT + "/retries").exchange().expectStatus().isForbidden();
        for (String role : new String[]{"ADMIN", "CREATOR", "REVIEWER"})
            http.post().uri(ROOT + "/retries").header("Authorization", "Bearer " + role).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(BODY).exchange().expectStatus().isForbidden();
        assertThat(CALLS.get()).isZero();
    }

    @Test void memberReconsiderationPreservesTheCompleteCommandAndPollingLocation() {
        String body = "{\"requestId\":\"11111111-1111-1111-1111-111111111111\",\"previousReviewId\":\"22222222-2222-2222-2222-222222222222\",\"reason\":\"재심사 요청 사유\"}";
        http.post().uri(ROOT + "/reconsiderations").header("Authorization", "Bearer MEMBER").header("X-Member-Id", "forged")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isAccepted().expectHeader().valueEquals("Location", ROOT);
        assertThat(REQUEST_PATH.get()).isEqualTo(ROOT + "/reconsiderations"); assertThat(REQUEST_BODY.get()).isEqualTo(body);
        assertThat(MEMBER_HEADER.get()).isNull(); assertThat(AUTHORIZATION.get()).isEqualTo("Bearer MEMBER");
    }

    @Test void nonMembersCannotSubmitReconsiderationsOrReadCustomerReviewHistory() {
        for (String role : new String[]{"ADMIN", "CREATOR", "REVIEWER"}) {
            http.post().uri(ROOT + "/reconsiderations").header("Authorization", "Bearer " + role).bodyValue("{}").exchange().expectStatus().isForbidden();
            http.get().uri(ROOT + "/reviews").header("Authorization", "Bearer " + role).exchange().expectStatus().isForbidden();
        }
        assertThat(CALLS.get()).isZero();
    }
}
