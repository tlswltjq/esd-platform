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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentAttemptFailureGatewayTest {
    private static final String PATH = "/api/v1/payments/11111111-1111-1111-1111-111111111111/attempt-failures";
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicReference<String> AUTH = new AtomicReference<>();
    private static final AtomicReference<String> URI = new AtomicReference<>();
    private static final HttpServer PAYMENT;
    static {
        try {
            PAYMENT = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            PAYMENT.createContext(PATH, exchange -> {
                CALLS.incrementAndGet(); AUTH.set(exchange.getRequestHeaders().getFirst("Authorization")); URI.set(exchange.getRequestURI().toString());
                byte[] body = "{\"success\":true,\"data\":{\"content\":[]}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
            });
            PAYMENT.start(); Runtime.getRuntime().addShutdownHook(new Thread(() -> PAYMENT.stop(0)));
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    @Autowired WebTestClient http;
    @MockitoBean ReactiveJwtDecoder decoder;
    @DynamicPropertySource static void payment(DynamicPropertyRegistry properties) { properties.add("PAYMENT_URI", () -> "http://127.0.0.1:" + PAYMENT.getAddress().getPort()); }
    @BeforeEach void tokens() {
        CALLS.set(0); AUTH.set(null); URI.set(null);
        when(decoder.decode(anyString())).thenAnswer(call -> Mono.just(Jwt.withTokenValue("token").header("alg", "RS256").subject("usr_buyer")
                .claim("roles", List.of(call.getArgument(0, String.class))).build()));
    }
    @Test void failureHistoryUsesTheExistingPaymentRouteAndPreservesBearerQueryAndNoStore() {
        http.get().uri(PATH + "?page=1&size=5").header("Authorization", "Bearer MEMBER").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store").expectBody().json("{\"success\":true,\"data\":{\"content\":[]}}");
        assertThat(CALLS.get()).isEqualTo(1); assertThat(AUTH.get()).isEqualTo("Bearer MEMBER"); assertThat(URI.get()).isEqualTo(PATH + "?page=1&size=5");
    }
    @Test void anonymousAndNonMemberRolesCannotReachPayment() {
        http.get().uri(PATH).exchange().expectStatus().isUnauthorized();
        for (String role : List.of("ADMIN", "CREATOR", "REVIEWER")) http.get().uri(PATH).header("Authorization", "Bearer " + role).exchange().expectStatus().isForbidden();
        assertThat(CALLS.get()).isZero();
    }
}
