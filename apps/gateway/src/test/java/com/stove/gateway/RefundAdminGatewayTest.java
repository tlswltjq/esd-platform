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
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RefundAdminGatewayTest {
    private static final String ROOT = "/api/v1/admin/refunds";
    private static final HttpServer ORDER;
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicReference<String> MEMBER_HEADER = new AtomicReference<>();
    private static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();
    static {
        try {
            ORDER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ORDER.createContext(ROOT, exchange -> {
                CALLS.incrementAndGet(); MEMBER_HEADER.set(exchange.getRequestHeaders().getFirst("X-Member-Id"));
                AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.getRequestBody().readAllBytes();
                byte[] body = "{\"success\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
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
        CALLS.set(0); MEMBER_HEADER.set(null); AUTHORIZATION.set(null);
        when(decoder.decode(anyString())).thenAnswer(call -> {
            String role = call.getArgument(0, String.class);
            return Mono.just(Jwt.withTokenValue(role).header("alg", "RS256").subject("operator")
                    .claim("roles", List.of(role)).build());
        });
    }
    @Test void administratorReadsAndDecisionReachOrderWithBearerAndWithoutForgedMemberHeader() {
        for (String path : new String[]{ROOT, ROOT + "/example"})
            http.get().uri(path).header("Authorization", "Bearer ADMIN").header("X-Member-Id", "forged")
                    .exchange().expectStatus().isOk();
        http.post().uri(ROOT + "/example/decision").header("Authorization", "Bearer ADMIN").header("X-Member-Id", "forged")
                .bodyValue("{}").exchange().expectStatus().isOk();
        assertThat(CALLS.get()).isEqualTo(3); assertThat(MEMBER_HEADER.get()).isNull();
        assertThat(AUTHORIZATION.get()).isEqualTo("Bearer ADMIN");
    }
    @Test void anonymousMembersAndCreatorsCannotReachTheReviewService() {
        http.get().uri(ROOT).exchange().expectStatus().isUnauthorized();
        for (String role : new String[]{"MEMBER", "CREATOR"}) {
            for (String path : new String[]{ROOT, ROOT + "/example"})
                http.get().uri(path).header("Authorization", "Bearer " + role).exchange().expectStatus().isForbidden();
            http.post().uri(ROOT + "/example/decision").header("Authorization", "Bearer " + role)
                    .bodyValue("{}").exchange().expectStatus().isForbidden();
        }
        assertThat(CALLS.get()).isZero();
    }
    @Test void unlistedAdministratorMethodsAndPathsRemainDenied() {
        for (HttpMethod method : new HttpMethod[]{HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH})
            http.method(method).uri(ROOT + "/example").header("Authorization", "Bearer ADMIN").exchange().expectStatus().isForbidden();
        http.post().uri(ROOT + "/example").header("Authorization", "Bearer ADMIN").exchange().expectStatus().isForbidden();
        http.get().uri(ROOT + "/example/decision").header("Authorization", "Bearer ADMIN").exchange().expectStatus().isForbidden();
        assertThat(CALLS.get()).isZero();
    }
}
