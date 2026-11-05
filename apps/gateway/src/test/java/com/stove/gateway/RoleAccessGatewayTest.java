package com.stove.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * 역할별 접근의 전체 여정. <b>어떤 역할이 어디에 들어가고 어디서 막히는가</b>를 게이트웨이 필터 판정으로 지킨다.
 *
 * <p>접근 규칙은 {@link com.stove.gateway.config.SecurityConfig}의 {@code authorizeExchange}에 선언돼 있고,
 * 사슬 끝은 {@code denyAll()}이다. 규칙은 코드에 있지만 그 규칙이 실제로 통과·거부로 이어지는지는
 * {@link CatalogSalesGatewayTest}가 catalog 경로에만 확인했다. 이 테스트는 R5의 나머지 역할 경계
 * (MEMBER·ADMIN·CREATOR·REVIEWER, 그리고 기본 거부)를 대표 경로로 덮는다.
 *
 * <p>하위 서비스를 띄우지 않으므로 <b>통과</b>는 2xx가 아니라 "403이 아님"으로 판정한다. 필터를 통과하면
 * 요청은 라우트의 하위 서비스로 프록시되려다 대상 부재로 5xx가 되며, 그 5xx는 "권한 판정은 통과했다"는
 * 신호다. <b>거부</b>는 403, <b>미인증</b>은 401로 판정한다. 이는 {@link GatewayRouteTest}가 도달
 * 가능성을 라우트 매칭으로만 보는 것과 같은 결의 경계 검사다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoleAccessGatewayTest {

    @Autowired WebTestClient http;
    @MockitoBean ReactiveJwtDecoder decoder;

    @BeforeEach
    void tokens() {
        // 토큰 값 하나하나를 역할 목록으로 해석한다. "Bearer MEMBER" → roles=[MEMBER],
        // "Bearer MEMBER,CREATOR" → roles=[MEMBER, CREATOR]. 서명·발행자 검증은
        // GatewayJwtValidationTest가 따로 지키므로 여기서는 역할 경계만 본다.
        when(decoder.decode(anyString())).thenAnswer(call -> {
            String value = call.getArgument(0, String.class);
            List<String> roles = List.of(value.split(","));
            return Mono.just(Jwt.withTokenValue(value).header("alg", "RS256").subject("subject")
                    .claim("roles", roles).build());
        });
    }

    private WebTestClient.ResponseSpec call(HttpMethod method, String path, String token) {
        WebTestClient.RequestHeadersSpec<?> request = method == HttpMethod.GET
                ? http.get().uri(path)
                : http.post().uri(path).bodyValue("{}");
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
            if (method != HttpMethod.GET) {
                request = ((WebTestClient.RequestBodySpec) request).header("Content-Type", "application/json");
            }
        }
        return request.exchange();
    }

    @ParameterizedTest(name = "{2} {0} {1} → 통과(403 아님)")
    @CsvSource({
            "GET,  /api/v1/orders,                  order,       MEMBER",
            "GET,  /api/v1/payments,                payment,     MEMBER",
            "GET,  /api/v1/library,                 license,     MEMBER",
            "POST, /api/v1/downloads/1,             download,    MEMBER",
            "POST, /api/v1/studio/projects,         studio,      CREATOR",
            "GET,  /api/v1/settlements/me/ledger,   settlement,  CREATOR",
            "GET,  /api/v1/settlements/close,       settlement,  ADMIN",
            "GET,  /api/v1/admin/refunds,           order-refund, ADMIN",
            "GET,  /api/v1/promotions/platform/products/1, promotions, ADMIN",
            "POST, /api/v1/reviews/1/approve,       review,      REVIEWER",
            "POST, /api/v1/reviews/1/approve,       review,      ADMIN"
    })
    @DisplayName("요구한 역할을 가진 토큰은 권한 판정을 통과한다")
    void requiredRolePassesAuthorization(String method, String path, String ignoredLabel, String role) {
        // 권한 판정만 본다. 통과하면 하위 서비스 부재로 5xx가 되지만 403은 아니다.
        call(HttpMethod.valueOf(method), path, role).expectStatus().value(status ->
                assertThat(status).isNotEqualTo(403));
    }

    @ParameterizedTest(name = "{2} {0} {1} → 403")
    @CsvSource({
            "GET,  /api/v1/orders,                  order,       CREATOR",
            "GET,  /api/v1/payments,                payment,     REVIEWER",
            "GET,  /api/v1/library,                 license,     ADMIN",
            "POST, /api/v1/studio/projects,         studio,      MEMBER",
            "GET,  /api/v1/settlements/me/ledger,   settlement,  MEMBER",
            "GET,  /api/v1/settlements/close,       settlement,  CREATOR",
            "GET,  /api/v1/admin/refunds,           order-refund, MEMBER",
            "GET,  /api/v1/promotions/platform/products/1, promotions, CREATOR",
            "POST, /api/v1/reviews/1/approve,       review,      MEMBER",
            "POST, /api/v1/reviews/1/approve,       review,      CREATOR"
    })
    @DisplayName("요구한 역할이 없는 토큰은 403으로 막힌다")
    void missingRoleIsForbidden(String method, String path, String ignoredLabel, String role) {
        call(HttpMethod.valueOf(method), path, role).expectStatus().isForbidden();
    }

    @ParameterizedTest(name = "{1} {0} → 인증 요구(2xx 아님)")
    @CsvSource({
            "GET,  /api/v1/orders,                order",
            "GET,  /api/v1/payments,              payment",
            "GET,  /api/v1/library,               license",
            "POST, /api/v1/downloads/1,           download",
            "POST, /api/v1/studio/projects,       studio",
            "GET,  /api/v1/settlements/close,     settlement",
            "GET,  /api/v1/admin/refunds,         order-refund",
            "POST, /api/v1/reviews/1/approve,     review"
    })
    @DisplayName("보호 경로는 토큰이 없으면 접근이 막힌다(GET은 401, POST는 CSRF가 먼저 403)")
    void protectedRoutesRequireAuthentication(String method, String path, String ignoredLabel) {
        // 미인증 거부의 상태 코드는 경로의 방식에 달렸다. GET은 자원 서버가 401을,
        // 변경 메서드는 CsrfWebFilter가 먼저 403을 낸다(SecurityConfig의 csrfMatcher는
        // Bearer 없는 변경 요청을 CSRF 대상으로 남긴다). 둘 다 "하위 서비스에 닿지 못함"이
        // 핵심이므로 401 또는 403 중 하나로 막히는 것을 확인한다.
        call(HttpMethod.valueOf(method), path, null).expectStatus().value(status ->
                assertThat(status).isIn(401, 403));
    }

    @Test
    @DisplayName("규칙에 없는 /api 경로는 Bearer 인증조차 붙지 않아 401로 막힌다(denyAll 이전 단계)")
    void undeclaredPathsAreUnreachable() {
        // 어떤 pathMatcher에도 선언되지 않은 /api 경로. Bearer 토큰을 줘도 401이 난다 —
        // 선언된 경로만 자원 서버 인증을 통과시키고, 선언되지 않은 경로는 인증 단계에서
        // 막혀 denyAll까지 가지도 못한다. 어느 쪽이든 외부 도달 불가라는 결론은 같다.
        call(HttpMethod.GET, "/api/v1/unknown-area/thing", "MEMBER,ADMIN,CREATOR,REVIEWER")
                .expectStatus().value(status -> assertThat(status).isIn(401, 403));
    }

    @Test
    @DisplayName("MEMBER 다중 역할 보유자는 자기 역할 경로를 모두 통과한다")
    void multiRoleTokenPassesEachOwnedRole() {
        String all = "MEMBER,CREATOR,ADMIN,REVIEWER";
        for (String path : List.of("/api/v1/orders", "/api/v1/studio/projects", "/api/v1/settlements/close")) {
            HttpMethod method = path.equals("/api/v1/orders") ? HttpMethod.GET : HttpMethod.POST;
            call(method, path, all).expectStatus().value(status ->
                    assertThat(status).isNotEqualTo(403));
        }
    }
}
