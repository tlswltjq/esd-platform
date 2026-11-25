package com.stove.webbff.config.bff;

import java.util.Set;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

/**
 * 결정 219·223: API 게이트웨이로 가는 라우트에서만 동작한다. 브라우저 쿠키는 세션 여부와 무관하게 늘 지우고(하류는 쿠키를
 * 쓰지 않는다), 세션 요청이면 서버 쪽 access token을 Bearer로 붙인다. Auth 브라우저 경로({@code auth-browser})는 Auth가
 * 자기 세션 쿠키로 로그인·로그아웃을 하므로 건드리지 않는다(PR #243 R1·R2).
 */
@Configuration
public class BffRelayFilter {
    static final Set<String> API_GATEWAY_ROUTES = Set.of("api-gateway", "api-docs");

    @Bean
    GlobalFilter bffTokenRelay() {
        return (exchange, chain) -> {
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            if (route == null || !API_GATEWAY_ROUTES.contains(route.getId())) return chain.filter(exchange);
            String access = exchange.getAttribute(BffAuthenticationFilter.ACCESS_TOKEN);
            return chain.filter(exchange.mutate().request(exchange.getRequest().mutate()
                    .headers(headers -> {
                        headers.remove(HttpHeaders.COOKIE);
                        if (access != null) headers.setBearerAuth(access);
                    }).build()).build());
        };
    }
}
