package com.stove.webbff.config.bff;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * PR #243 R1·R2·R3: 릴레이는 라우트 ID로만 대상을 정한다. 세션 토큰이 이미 꺼내져 있어도(앞 필터의 경로 조건과 무관하게)
 * Auth 브라우저 경로에는 토큰을 붙이지 않고 쿠키를 건드리지 않는다. 게이트웨이행 라우트에서는 쿠키를 늘 지운다.
 */
class BffRelayFilterTest {
    private final GlobalFilter relay = new BffRelayFilter().bffTokenRelay();

    private ServerHttpRequest relayed(String routeId, String access) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/any")
                .header(HttpHeaders.COOKIE, "SESSION=browser; JSESSIONID=auth").build());
        if (routeId != null) {
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                    Route.async().id(routeId).uri(URI.create("http://downstream")).predicate(ignored -> true).build());
        }
        if (access != null) {
            exchange.getAttributes().put(BffAuthenticationFilter.ACCESS_TOKEN, access);
        }
        AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
        relay.filter(exchange, next -> {
            forwarded.set(next.getRequest());
            return Mono.empty();
        }).block();
        return forwarded.get();
    }

    @Test
    void authBrowserRouteGetsNoTokenAndKeepsCookiesEvenWhenASessionTokenWasResolved() {
        ServerHttpRequest request = relayed("auth-browser", "server-access");

        assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
        assertThat(request.getHeaders().getFirst(HttpHeaders.COOKIE)).isEqualTo("SESSION=browser; JSESSIONID=auth");
    }

    @ParameterizedTest
    @ValueSource(strings = {"api-gateway", "api-docs"})
    void apiGatewayRoutesAlwaysDropCookiesAndCarryTheSessionToken(String routeId) {
        ServerHttpRequest withSession = relayed(routeId, "server-access");
        assertThat(withSession.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer server-access");
        assertThat(withSession.getHeaders().get(HttpHeaders.COOKIE)).isNull();

        ServerHttpRequest anonymous = relayed(routeId, null);
        assertThat(anonymous.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
        assertThat(anonymous.getHeaders().get(HttpHeaders.COOKIE)).isNull();
    }

    @Test
    void requestsWithoutARouteAreLeftAlone() {
        ServerHttpRequest request = relayed(null, "server-access");

        assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
        assertThat(request.getHeaders().getFirst(HttpHeaders.COOKIE)).isEqualTo("SESSION=browser; JSESSIONID=auth");
    }
}
