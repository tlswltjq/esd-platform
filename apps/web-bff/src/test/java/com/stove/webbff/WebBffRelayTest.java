package com.stove.webbff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.stove.webbff.config.bff.BffTokens;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * 결정 220·223·224: 웹 BFF는 외부 자격 증명을 받지 않고, 세션 요청에만 서버 쪽 access token을 붙여 API 게이트웨이로 넘긴다.
 * 하류(API 게이트웨이)는 받은 헤더를 기록하는 가짜 서버다. 세션은 {@link BffSessionFixture}가 만든다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BffSessionFixture.class)
class WebBffRelayTest {
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();
    private static final AtomicReference<String> COOKIE = new AtomicReference<>();
    private static final AtomicInteger AUTH_CALLS = new AtomicInteger();
    private static final AtomicReference<String> AUTH_AUTHORIZATION = new AtomicReference<>();
    private static final AtomicReference<String> AUTH_COOKIE = new AtomicReference<>();
    private static final HttpServer API_GATEWAY;

    static {
        try {
            API_GATEWAY = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            API_GATEWAY.createContext("/api", exchange -> {
                CALLS.incrementAndGet();
                AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                COOKIE.set(exchange.getRequestHeaders().getFirst("Cookie"));
                byte[] bytes = "{\"success\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            // 같은 가짜 서버가 Auth 브라우저 경로도 받는다(AUTH_URI). 기록은 API 게이트웨이와 따로 한다.
            API_GATEWAY.createContext("/connect", exchange -> {
                AUTH_CALLS.incrementAndGet();
                AUTH_AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                AUTH_COOKIE.set(String.join("; ", exchange.getRequestHeaders().getOrDefault("Cookie", List.of())));
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            API_GATEWAY.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> API_GATEWAY.stop(0)));
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @DynamicPropertySource
    static void apiGateway(DynamicPropertyRegistry registry) {
        registry.add("API_GATEWAY_URI", () -> "http://127.0.0.1:" + API_GATEWAY.getAddress().getPort());
        registry.add("AUTH_URI", () -> "http://127.0.0.1:" + API_GATEWAY.getAddress().getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ReactiveClientRegistrationRepository registrations;
    @MockitoBean BffTokens tokens;
    @MockitoBean ReactiveJwtDecoder decoder;
    private BffSessionFixture.Sessions sessions;

    @BeforeEach
    void reset() {
        CALLS.set(0);
        AUTHORIZATION.set(null);
        COOKIE.set(null);
        AUTH_CALLS.set(0);
        AUTH_AUTHORIZATION.set(null);
        AUTH_COOKIE.set(null);
        sessions = BffSessionFixture.sessions(http, tokens, registrations);
        when(decoder.decode(anyString())).thenAnswer(call -> {
            String value = call.getArgument(0, String.class);
            return Mono.just(Jwt.withTokenValue(value).header("alg", "RS256").subject("subject")
                    .claim("roles", List.of(value.split(","))).build());
        });
    }

    @Test
    @DisplayName("세션 요청은 서버 쪽 access token을 Bearer로 붙이고 브라우저 쿠키는 넘기지 않는다")
    void sessionRequestsRelayTheServerSideTokenWithoutTheBrowserCookie() {
        http.get().uri("/api/v1/orders").headers(sessions.as("MEMBER")).exchange().expectStatus().isOk();

        assertThat(CALLS.get()).isEqualTo(1);
        assertThat(AUTHORIZATION.get()).isEqualTo("Bearer MEMBER");
        assertThat(COOKIE.get()).isNull();
    }

    @Test
    @DisplayName("세션 없는 요청은 자격 증명 없이 넘긴다 — 보호 경로의 401·403은 API 게이트웨이가 정한다")
    void anonymousRequestsAreForwardedWithoutCredentials() {
        http.get().uri("/api/v1/products/1").header("Cookie", "SESSION=unknown; tracking=1").exchange().expectStatus().isOk();

        assertThat(CALLS.get()).isEqualTo(1);
        assertThat(AUTHORIZATION.get()).isNull();
        assertThat(COOKIE.get()).as("PR #243 R1: 세션이 없어도 브라우저 쿠키는 하류에 닿지 않는다").isNull();
    }

    @Test
    @DisplayName("PR #243 R2: Auth 브라우저 경로에는 세션이 있어도 토큰을 붙이지 않고 쿠키를 그대로 넘긴다")
    void authBrowserRoutesKeepTheirCookiesAndGetNoToken() {
        http.get().uri("/connect/logout").headers(sessions.as("MEMBER")).header("Cookie", "JSESSIONID=auth-session")
                .exchange().expectStatus().isNoContent();

        assertThat(AUTH_CALLS.get()).isEqualTo(1);
        assertThat(AUTH_AUTHORIZATION.get()).isNull();
        assertThat(AUTH_COOKIE.get()).contains("JSESSIONID=auth-session");
        assertThat(CALLS.get()).isZero();
    }

    @Test
    @DisplayName("세션 쿠키의 변경 요청은 CSRF 헤더가 있어야 넘어간다")
    void sessionChangesNeedTheCsrfHeader() {
        http.post().uri("/api/v1/orders").header("Cookie", "SESSION=unknown").header("Content-Type", "application/json")
                .bodyValue("{}").exchange().expectStatus().isForbidden();
        assertThat(CALLS.get()).isZero();

        http.post().uri("/api/v1/orders").headers(sessions.as("MEMBER")).header("Content-Type", "application/json")
                .bodyValue("{}").exchange().expectStatus().isOk();
        assertThat(CALLS.get()).isEqualTo(1);
        assertThat(AUTHORIZATION.get()).isEqualTo("Bearer MEMBER");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/v1/products/1", "/api/v1/orders", "/bff/session"})
    @DisplayName("결정 220: 외부가 보낸 Authorization 헤더는 공개 경로든 세션이 있든 401이고 하류에 닿지 않는다")
    void externalAuthorizationHeaderIsRejectedBeforeRelay(String path) {
        http.get().uri(path).header("Authorization", "Bearer MEMBER").exchange().expectStatus().isUnauthorized();
        http.get().uri(path).headers(sessions.as("MEMBER")).header("Authorization", "Bearer ADMIN")
                .exchange().expectStatus().isUnauthorized();

        assertThat(CALLS.get()).isZero();
    }
}
