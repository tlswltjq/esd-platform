package com.stove.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 게이트웨이 라우팅. <b>무엇이 통과하는가</b>보다 <b>무엇이 통과하지 않는가</b>가 핵심이다.
 *
 * <p>이 시스템의 외부 노출 통제는 필터가 아니라 <b>라우트의 부재</b>로 표현돼 있다.
 * 운영툴 API 는 차단 규칙이 있어서 막히는 게 아니라 <b>매칭되는 라우트가 없어서</b> 막힌다.
 * 그래서 술어를 한 줄 넓히는 것만으로 조용히 열리고, 코드를 읽어도 눈에 띄지 않는다.
 *
 * <p>하위 서비스를 띄우지 않는다. 검증 대상은 프록시 동작이 아니라 <b>술어 판정</b>이므로
 * {@link RouteLocator} 의 라우트에 요청을 직접 걸어 어디로 매칭되는지만 본다.
 */
@SpringBootTest
class GatewayRouteTest {

    @Autowired
    private RouteLocator routeLocator;

    /** @return 이 요청이 매칭되는 라우트 id. 어디에도 안 걸리면 null — 즉 외부에서 도달 불가. */
    private String matchedRouteId(HttpMethod method, String path) {
        return routeLocator.getRoutes()
                .filterWhen(route -> Mono.from(route.getPredicate().apply(exchange(method, path))))
                .map(Route::getId)
                .next()
                .block();
    }

    /** 라우트마다 새 exchange 를 준다 — 술어가 매칭 결과를 exchange 속성에 남기기 때문이다. */
    private static MockServerWebExchange exchange(HttpMethod method, String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(method, path).build());
    }

    @ParameterizedTest(name = "POST {0}")
    @ValueSource(strings = {
            "/api/v1/products/quote",
            "/api/v1/products/1/sale-open",
            "/api/v1/products/1/suspend",
            "/api/v1/products/reindex"
    })
    @DisplayName("catalog 의 운영·내부 POST 는 외부에서 도달할 수 없다")
    void catalogWriteEndpointsAreUnreachable(String path) {
        // catalog-public 라우트를 지탱하는 것은 Method=GET 술어 한 줄이다.
        // 그 줄이 사라지거나 Path 가 넓어지면 아래 넷이 전부 인터넷에 열린다.
        //
        // 특히 /quote 는 서버측 금액 재계산 경로다 — D-009(금액 조작)의 공격면 그 자체이고,
        // sale-open/suspend 는 상품 상태를 임의로 전환할 수 있다.
        assertThat(matchedRouteId(HttpMethod.POST, path))
                .as("외부에서 호출 가능해졌다")
                .isNull();
    }

    @ParameterizedTest(name = "{0} /api/v1/products/1")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    @DisplayName("catalog 는 조회 외의 메서드를 일절 받지 않는다")
    void catalogAcceptsReadsOnly(String method) {
        assertThat(matchedRouteId(HttpMethod.valueOf(method), "/api/v1/products/1")).isNull();
    }

    @Test
    @DisplayName("상품 조회는 열려 있다 — 차단이 정상 경로까지 막지 않는다")
    void productLookupIsPublic() {
        assertThat(matchedRouteId(HttpMethod.GET, "/api/v1/products/1")).isEqualTo("catalog-public");
        assertThat(matchedRouteId(HttpMethod.GET, "/api/v1/products")).isEqualTo("catalog-public");
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({
            "GET,  /api/v1/storefront/main,     store",
            "POST, /api/v1/orders,              order",
            "POST, /api/v1/payments/callback,   payment",
            "GET,  /api/v1/library,             license",
            "POST, /api/v1/downloads/1,         download",
            "POST, /api/v1/studio/games,        studio",
            "POST, /api/v1/reviews/1/approve,   review-admin",
            "POST, /api/v1/settlements/close,   settlement-admin"
    })
    @DisplayName("나머지 라우트는 의도한 서비스로 간다")
    void routesReachTheirService(String method, String path, String expectedRouteId) {
        assertThat(matchedRouteId(HttpMethod.valueOf(method), path)).isEqualTo(expectedRouteId);
    }

    @Test
    @DisplayName("정의되지 않은 경로는 어디에도 매칭되지 않는다")
    void unknownPathMatchesNothing() {
        assertThat(matchedRouteId(HttpMethod.GET, "/api/v1/unknown")).isNull();
        assertThat(matchedRouteId(HttpMethod.GET, "/actuator/env")).isNull();
    }

    @ParameterizedTest(name = "GET /v3/api-docs/{0}")
    @ValueSource(strings = {
            "store", "catalog", "order", "payment", "license",
            "download", "studio", "review", "settlement"
    })
    @DisplayName("9개 서비스의 명세가 게이트웨이를 통해 조회된다 — Swagger UI 가 같은 출처에서 받는다")
    void apiDocsAreProxied(String service) {
        assertThat(matchedRouteId(HttpMethod.GET, "/v3/api-docs/" + service))
                .isEqualTo("docs-" + service);
    }

    @ParameterizedTest(name = "{0} /v3/api-docs/order")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    @DisplayName("명세 프록시는 읽기 전용이다 — 하위 서비스로 가는 쓰기 통로가 되지 않는다")
    void apiDocsRoutesAreReadOnly(String method) {
        assertThat(matchedRouteId(HttpMethod.valueOf(method), "/v3/api-docs/order")).isNull();
    }

    @Test
    @DisplayName("명세 프록시는 그 경로 하나씩만 연다 — 하위 경로로 넓어지지 않는다")
    void apiDocsRoutesDoNotWiden() {
        // Path 를 /v3/api-docs/order/** 로 넓히면 SetPath 가 붙기 전 경로가 그대로 하위로 흘러
        // 명세 프록시가 임의 GET 통로가 된다. 아래 둘이 그 회귀를 잡는다.
        assertThat(matchedRouteId(HttpMethod.GET, "/v3/api-docs/order/actuator/env")).isNull();
        assertThat(matchedRouteId(HttpMethod.GET, "/v3/api-docs/unknown")).isNull();
    }
}
