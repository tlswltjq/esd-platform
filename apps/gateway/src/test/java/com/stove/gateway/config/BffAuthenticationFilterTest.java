package com.stove.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stove.gateway.config.bff.BffAuthenticationFilter;
import com.stove.gateway.config.bff.BffTokens;
import com.stove.gateway.config.bff.RedisAuthorizedClients;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

class BffAuthenticationFilterTest {
    @Test
    void rejectedRefreshInvalidatesBrowserSessionAndStopsRequest() {
        RedisAuthorizedClients clients = mock(RedisAuthorizedClients.class);
        BffTokens tokens = mock(BffTokens.class);
        ReactiveJwtDecoder decoder = mock(ReactiveJwtDecoder.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/bff/session"));
        WebSession session = exchange.getSession().block();
        session.start();
        session.getAttributes().put("logged-in", true);
        when(clients.key(exchange)).thenReturn(Mono.just("session-key"));
        when(tokens.current("session-key")).thenReturn(
                Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED)));
        DefaultOAuth2User user = new DefaultOAuth2User(List.of(), Map.of("sub", "member"), "sub");
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(user, List.of(), "gateway-bff");
        AtomicBoolean forwarded = new AtomicBoolean();

        new BffAuthenticationFilter(clients, tokens, decoder).filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(session.isStarted()).isFalse();
        assertThat(session.getAttributes()).isEmpty();
        assertThat(forwarded).isFalse();
        verify(tokens).current("session-key");
    }
}
