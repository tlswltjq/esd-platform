package com.stove.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.stove.common.testcontainers.InfraContainers;
import com.stove.gateway.config.bff.BffTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.session.ReactiveSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "BFF_CLIENT_SECRET=logout-integration-secret")
@Import(InfraContainers.Redis.class)
class BffLogoutIntegrationTest {
    @Autowired WebTestClient http;
    @Autowired @SuppressWarnings("rawtypes") ReactiveSessionRepository sessions;
    @MockitoBean BffTokens tokens;

    @Test
    void csrfCleanupCompletesBeforeRedisSessionInvalidation() {
        Session session = (Session) sessions.createSession().block(Duration.ofSeconds(5));
        var idToken = new OidcIdToken("test-id-token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("sub", "member", "iss", "http://localhost:8091", "aud", List.of("gateway-bff")));
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_MEMBER"));
        var user = new DefaultOidcUser(authorities, idToken);
        var authentication = new OAuth2AuthenticationToken(user, authorities, "gateway-bff");
        session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(authentication));
        session.setAttribute("bff.client-key", "logout-test");
        sessions.save(session).block(Duration.ofSeconds(5));
        when(tokens.revoke(anyString())).thenReturn(Mono.empty());

        var csrfResult = http.get().uri("/bff/csrf").cookie("SESSION", session.getId()).exchange()
                .expectStatus().isOk().expectBody(Map.class).returnResult();
        Map<?, ?> csrf = csrfResult.getResponseBody();
        var updatedCookie = csrfResult.getResponseCookies().getFirst("SESSION");
        String cookie = updatedCookie == null ? session.getId() : updatedCookie.getValue();
        assertThat(csrf).isNotNull();
        http.post().uri("/bff/logout").cookie("SESSION", cookie)
                .header(csrf.get("headerName").toString(), csrf.get("token").toString()).exchange()
                .expectStatus().isFound()
                .expectHeader().value("Location", value -> assertThat(value).contains("/connect/logout"));
        assertThat(sessions.findById(session.getId()).block(Duration.ofSeconds(5))).isNull();
    }
}
