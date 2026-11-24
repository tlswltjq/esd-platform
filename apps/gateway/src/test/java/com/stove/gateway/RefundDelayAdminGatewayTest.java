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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import com.stove.gateway.config.bff.BffTokens;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BffSessionFixture.class)
class RefundDelayAdminGatewayTest {
    private static final String ROOT = "/api/v1/admin/refund-delays";
    private static final String COMMAND = "{\"requestId\":\"11111111-1111-1111-1111-111111111111\",\"note\":\"reviewed\"}";
    private static final HttpServer PAYMENT;
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicReference<String> MEMBER_HEADER = new AtomicReference<>();
    private static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();
    private static final AtomicReference<String> REQUEST_BODY = new AtomicReference<>();
    private static final AtomicReference<String> REQUEST_URI = new AtomicReference<>();
    static {
        try {
            PAYMENT = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            PAYMENT.createContext(ROOT, exchange -> {
                CALLS.incrementAndGet(); MEMBER_HEADER.set(exchange.getRequestHeaders().getFirst("X-Member-Id"));
                AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                REQUEST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                REQUEST_URI.set(exchange.getRequestURI().toString());
                byte[] body = "{\"success\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("Location", ROOT);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
            });
            PAYMENT.start(); Runtime.getRuntime().addShutdownHook(new Thread(() -> PAYMENT.stop(0)));
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    @Autowired WebTestClient http;
    @MockitoBean ReactiveJwtDecoder decoder;
    @MockitoBean BffTokens tokens;
    @Autowired ReactiveClientRegistrationRepository registrations;
    private BffSessionFixture.Sessions sessions;
    @DynamicPropertySource static void payment(DynamicPropertyRegistry properties) {
        properties.add("PAYMENT_URI", () -> "http://127.0.0.1:" + PAYMENT.getAddress().getPort());
    }
    @BeforeEach void tokens() {
        sessions = BffSessionFixture.sessions(http, tokens, registrations);
        CALLS.set(0); MEMBER_HEADER.set(null); AUTHORIZATION.set(null); REQUEST_BODY.set(null); REQUEST_URI.set(null);
        when(decoder.decode(anyString())).thenAnswer(call -> {
            String role = call.getArgument(0, String.class);
            return Mono.just(Jwt.withTokenValue(role).header("alg", "RS256").subject("operator")
                    .claim("roles", List.of(role)).build());
        });
    }
    @Test void administratorReadsAndInvestigationReachPaymentWithBearerAndWithoutForgedMemberHeader() {
        for (String path : new String[]{ROOT + "?page=1&size=5", ROOT + "/example", ROOT + "/example/investigations"}) {
            http.get().uri(path).headers(sessions.as("ADMIN")).header("X-Member-Id", "forged")
                    .exchange().expectStatus().isOk();
            assertThat(REQUEST_URI.get()).isEqualTo(path);
        }
        http.post().uri(ROOT + "/example/investigations").headers(sessions.as("ADMIN")).header("X-Member-Id", "forged")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(COMMAND).exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store").expectHeader().valueEquals("Location", ROOT);
        assertThat(CALLS.get()).isEqualTo(4); assertThat(MEMBER_HEADER.get()).isNull();
        assertThat(AUTHORIZATION.get()).isEqualTo("Bearer ADMIN");
        assertThat(REQUEST_BODY.get()).isEqualTo(COMMAND); assertThat(REQUEST_URI.get()).isEqualTo(ROOT + "/example/investigations");
    }
    @Test void anonymousMembersAndCreatorsCannotReachThePaymentService() {
        http.get().uri(ROOT).exchange().expectStatus().isUnauthorized();
        for (String role : new String[]{"MEMBER", "CREATOR", "REVIEWER"}) {
            for (String path : new String[]{ROOT, ROOT + "/example", ROOT + "/example/investigations"})
                http.get().uri(path).headers(sessions.as(role)).exchange().expectStatus().isForbidden();
            http.post().uri(ROOT + "/example/investigations").headers(sessions.as(role))
                    .bodyValue("{}").exchange().expectStatus().isForbidden();
        }
        assertThat(CALLS.get()).isZero();
    }
    /** Decision 127 (PR #157 R1): the operator closure reaches Payment through the gateway; other roles never do. */
    @Test void onlyAnAdministratorClosureReachesPaymentWithBearerAndWithoutForgedMemberHeader() {
        String closure = "{\"requestId\":\"11111111-1111-1111-1111-111111111111\",\"attribution\":\"EXTERNAL_CANCELLATION\",\"note\":\"console\"}";
        // Without a bearer token a POST is a browser-session request and the CSRF filter refuses it first.
        http.post().uri(ROOT + "/example/closure").exchange().expectStatus().isForbidden();
        for (String role : new String[]{"MEMBER", "CREATOR", "REVIEWER"})
            http.post().uri(ROOT + "/example/closure").headers(sessions.as(role))
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(closure).exchange().expectStatus().isForbidden();
        assertThat(CALLS.get()).isZero();
        http.post().uri(ROOT + "/example/closure").headers(sessions.as("ADMIN")).header("X-Member-Id", "forged")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(closure).exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store");
        assertThat(CALLS.get()).isEqualTo(1); assertThat(MEMBER_HEADER.get()).isNull();
        assertThat(AUTHORIZATION.get()).isEqualTo("Bearer ADMIN");
        assertThat(REQUEST_BODY.get()).isEqualTo(closure); assertThat(REQUEST_URI.get()).isEqualTo(ROOT + "/example/closure");
        for (HttpMethod method : new HttpMethod[]{HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE})
            http.method(method).uri(ROOT + "/example/closure").headers(sessions.as("ADMIN")).exchange().expectStatus().isForbidden();
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @Test void unlistedAdministratorMethodsAndPathsRemainDenied() {
        for (HttpMethod method : new HttpMethod[]{HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH})
            http.method(method).uri(ROOT + "/example").headers(sessions.as("ADMIN")).exchange().expectStatus().isForbidden();
        http.post().uri(ROOT + "/example").headers(sessions.as("ADMIN")).exchange().expectStatus().isForbidden();
        http.post().uri(ROOT + "/example/close").headers(sessions.as("ADMIN")).bodyValue("{}").exchange().expectStatus().isForbidden();
        assertThat(CALLS.get()).isZero();
    }
}
