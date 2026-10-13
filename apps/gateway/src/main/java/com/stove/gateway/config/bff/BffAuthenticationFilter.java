package com.stove.gateway.config.bff;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Session authentication is converted to validated JWT roles for this request only. */
public class BffAuthenticationFilter implements WebFilter {
    public static final String ACCESS_TOKEN = BffAuthenticationFilter.class.getName() + ".access";
    private final RedisAuthorizedClients clients;
    private final BffTokens tokens;
    private final ReactiveJwtDecoder decoder;
    public BffAuthenticationFilter(RedisAuthorizedClients clients, BffTokens tokens, ReactiveJwtDecoder decoder) {
        this.clients = clients;
        this.tokens = tokens;
        this.decoder = decoder;
    }
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/api/") && !path.equals("/bff/session")) return chain.filter(exchange);
        return ReactiveSecurityContextHolder.getContext()
                .filter(context -> context.getAuthentication() instanceof OAuth2AuthenticationToken)
                .flatMap(context -> clients.key(exchange).flatMap(tokens::current)
                        .flatMap(client -> decoder.decode(client.getAccessToken().getTokenValue()))
                        .map(jwt -> {
                            List<String> roles = jwt.getClaimAsStringList("roles");
                            exchange.getAttributes().put(ACCESS_TOKEN, jwt.getTokenValue());
                            return new JwtAuthenticationToken(jwt, roles == null ? List.of() : roles.stream()
                                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList());
                        }))
                .map(authentication -> chain.filter(exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)))
                .switchIfEmpty(Mono.fromSupplier(() -> chain.filter(exchange)))
                .flatMap(result -> result)
                .onErrorResume(org.springframework.web.server.ResponseStatusException.class, error -> {
                    if (error.getStatusCode().value() != 401) return Mono.error(error);
                    return exchange.getSession().flatMap(session -> session.invalidate()).then(Mono.defer(() -> {
                        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                        return exchange.getResponse().setComplete();
                    }));
                })
                .onErrorResume(org.springframework.security.oauth2.jwt.JwtException.class, error ->
                        exchange.getSession().flatMap(session -> session.invalidate()).then(Mono.defer(() -> {
                            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                            return exchange.getResponse().setComplete();
                        })));
    }
}
