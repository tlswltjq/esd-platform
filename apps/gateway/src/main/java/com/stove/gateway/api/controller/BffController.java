package com.stove.gateway.api.controller;

import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public class BffController {
    @GetMapping("/bff/csrf")
    public Mono<Map<String, String>> csrf(ServerWebExchange exchange) {
        Mono<CsrfToken> csrf = exchange.getAttribute(CsrfToken.class.getName());
        return csrf.map(token -> Map.of("headerName", token.getHeaderName(), "token", token.getToken()));
    }
    @GetMapping("/bff/session")
    public Map<String, Object> session(Authentication authentication) {
        JwtAuthenticationToken jwt = (JwtAuthenticationToken) authentication;
        return Map.of("subject", jwt.getName(), "memberId", jwt.getToken().getClaim("member_id"),
                "roles", jwt.getToken().getClaimAsStringList("roles"));
    }
}
