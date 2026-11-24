package com.stove.webbff.config.bff;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 결정 220: 외부 요청의 인증은 BFF 세션뿐이다. 외부가 보낸 {@code Authorization} 헤더는 경로와 무관하게 401이다.
 * 자원 서버만 지우면 공개 허용 경로에서 이 헤더가 하류로 그대로 넘어가, 하류의 JWT 인증이 Gateway를 우회한다.
 * 하류로 보내는 Bearer는 세션 요청에 한해 {@link BffRelayFilter}가 서버 쪽 토큰으로 붙인다.
 */
public final class ExternalAuthorizationRejectFilter implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }
}
