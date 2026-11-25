package com.stove.webbff;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 결정 223·224: 웹 BFF의 라우트는 세 갈래뿐이다 — Auth 브라우저 로그인 경로는 Auth로, 업무 API와 서비스 명세는
 * 내부 API 게이트웨이로. 경로 표와 역할 규칙은 게이트웨이가 가진다({@code GatewayRouteTest}·{@code RoleAccessGatewayTest}).
 */
@SpringBootTest
@Import(BffSessionFixture.class)
class WebBffRouteTest {

    @Autowired private RouteLocator routeLocator;

    private String matchedRouteId(HttpMethod method, String path) {
        return routeLocator.getRoutes()
                .filterWhen(route -> Mono.from(route.getPredicate().apply(
                        MockServerWebExchange.from(MockServerHttpRequest.method(method, path).build()))))
                .map(Route::getId).next().block();
    }

    @ParameterizedTest(name = "GET {0}")
    @ValueSource(strings = {"/oauth2/authorize", "/login", "/logout", "/connect/logout", "/default-ui.css",
            "/.well-known/openid-configuration"})
    @DisplayName("BFF 로그인에 필요한 브라우저 인증 경로만 Auth로 프록시한다")
    void browserAuthenticationRoutesReachAuth(String path) {
        assertThat(matchedRouteId(HttpMethod.GET, path)).isEqualTo("auth-browser");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/oauth2/token", "/oauth2/revoke", "/oauth2/introspect", "/oauth2/jwks", "/userinfo"})
    @DisplayName("결정 220: Auth의 토큰·폐기·검사·JWKS·userinfo 엔드포인트는 라우트가 없다")
    void authTokenEndpointsHaveNoRoute(String path) {
        assertThat(matchedRouteId(HttpMethod.POST, path)).isNull();
        assertThat(matchedRouteId(HttpMethod.GET, path)).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/v1/orders", "/api/v1/products/1", "/api/v1/auth/signup", "/api/v1/admin/refunds/1/decision"})
    @DisplayName("업무 API는 모두 API 게이트웨이로 넘긴다")
    void apiRequestsGoToTheApiGateway(String path) {
        assertThat(matchedRouteId(HttpMethod.GET, path)).isEqualTo("api-gateway");
        assertThat(matchedRouteId(HttpMethod.POST, path)).isEqualTo("api-gateway");
    }

    @Test
    @DisplayName("서비스 명세는 GET만, 정한 10개 경로만 API 게이트웨이로 넘긴다")
    void serviceDocsGoToTheApiGatewayOnlyForKnownServices() {
        assertThat(matchedRouteId(HttpMethod.GET, "/v3/api-docs/order")).isEqualTo("api-docs");
        assertThat(matchedRouteId(HttpMethod.POST, "/v3/api-docs/order")).isNull();
        assertThat(matchedRouteId(HttpMethod.GET, "/v3/api-docs/unknown")).isNull();
        assertThat(matchedRouteId(HttpMethod.GET, "/internal/v1/products/game/build")).isNull();
        assertThat(matchedRouteId(HttpMethod.GET, "/actuator/env")).isNull();
    }
}
