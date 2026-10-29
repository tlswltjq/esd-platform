package com.stove.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.stove.common.testcontainers.InfraContainers;
import com.stove.gateway.config.bff.BffTokens;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.session.Session;
import org.springframework.session.ReactiveSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="BFF_CLIENT_SECRET=approval-session-secret")
@Import(InfraContainers.Redis.class)
class PaymentApprovalSessionIntegrationTest {
    private static final String PATH="/api/v1/payments/11111111-1111-1111-1111-111111111111/approvals";
    private static final AtomicInteger CALLS=new AtomicInteger();
    private static final AtomicReference<String> AUTH=new AtomicReference<>();
    private static final HttpServer PAYMENT;
    static {
        try {
            PAYMENT=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            PAYMENT.createContext(PATH,exchange->{
                CALLS.incrementAndGet(); AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] bytes="{\"success\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(202,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            });
            PAYMENT.start(); Runtime.getRuntime().addShutdownHook(new Thread(()->PAYMENT.stop(0)));
        } catch(Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    @DynamicPropertySource static void payment(DynamicPropertyRegistry registry) {
        registry.add("PAYMENT_URI",()->"http://127.0.0.1:"+PAYMENT.getAddress().getPort());
    }
    @Autowired WebTestClient http;
    @Autowired ReactiveSessionRepository<?> sessions;
    @Autowired ReactiveClientRegistrationRepository registrations;
    @MockitoBean BffTokens tokens;
    @MockitoBean ReactiveJwtDecoder decoder;

    @Test void anAuthenticatedRedisSessionNeedsCsrfAndForwardsItsServerSideAccessToken() { verifySession(sessions); }
    private <S extends Session> void verifySession(ReactiveSessionRepository<S> repository) {
        CALLS.set(0); AUTH.set(null);
        S session=repository.createSession().block(Duration.ofSeconds(5));
        Instant now=Instant.now();
        OidcIdToken id=new OidcIdToken("fixture-id-token",now,now.plusSeconds(300),Map.of("sub","member","iss","http://localhost:8091","aud",List.of("gateway-bff")));
        List<SimpleGrantedAuthority> roles=List.of(new SimpleGrantedAuthority("ROLE_MEMBER"));
        session.setAttribute("SPRING_SECURITY_CONTEXT",new SecurityContextImpl(new OAuth2AuthenticationToken(new DefaultOidcUser(roles,id),roles,"gateway-bff")));
        session.setAttribute("bff.client-key","approval-session"); repository.save(session).block(Duration.ofSeconds(5));
        ClientRegistration registration=registrations.findByRegistrationId("gateway-bff").block(Duration.ofSeconds(5));
        OAuth2AccessToken access=new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,"server-access",now,now.plusSeconds(300),Set.of("commerce"));
        when(tokens.current(anyString())).thenReturn(Mono.just(new OAuth2AuthorizedClient(registration,"member",access)));
        when(decoder.decode("server-access")).thenReturn(Mono.just(Jwt.withTokenValue("server-access").header("alg","RS256").subject("member").claim("roles",List.of("MEMBER")).build()));
        try {
            http.post().uri(PATH).cookie("SESSION",session.getId()).bodyValue("{}").exchange().expectStatus().isForbidden();
            assertThat(CALLS.get()).isZero();
            EntityExchangeResult<Map> reply=http.get().uri("/bff/csrf").cookie("SESSION",session.getId()).exchange().expectStatus().isOk().expectBody(Map.class).returnResult();
            Map<?,?> csrf=reply.getResponseBody(); assertThat(csrf).isNotNull();
            ResponseCookie updated=reply.getResponseCookies().getFirst("SESSION"); String cookie=updated==null?session.getId():updated.getValue();
            http.post().uri(PATH).cookie("SESSION",cookie).header(csrf.get("headerName").toString(),csrf.get("token").toString())
                    .header("Content-Type","application/json").bodyValue("{}").exchange().expectStatus().isAccepted();
            assertThat(CALLS.get()).isEqualTo(1); assertThat(AUTH.get()).isEqualTo("Bearer server-access");
        } finally { repository.deleteById(session.getId()).block(Duration.ofSeconds(5)); }
    }
}
