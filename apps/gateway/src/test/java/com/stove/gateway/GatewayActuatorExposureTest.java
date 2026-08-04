package com.stove.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 게이트웨이가 <b>자기 자신</b>으로 무엇을 서비스하는가.
 *
 * <p>{@link GatewayRouteTest} 는 {@code RouteLocator} 에 요청을 걸어 <b>어디로 프록시되는지</b>만 본다.
 * 그래서 거기서 "매칭되는 라우트가 없다"는 것은 <b>도달 불가</b>를 뜻하지 않는다 —
 * 게이트웨이 자신의 엔드포인트(actuator)는 라우팅을 거치지 않고 그대로 응답한다.
 * {@code unknownPathMatchesNothing} 이 {@code /actuator/env} 를 확인하는 것도 실제로는
 * "프록시 대상이 아니다"까지만 말한다. 그래서 여기서는 <b>서버에 실제로 요청을 보내</b> 확인한다.
 *
 * <p>{@code application.yml} 의 {@code exposure.include} 에는 {@code gateway} 가 들어 있지만
 * 이 엔드포인트는 그것만으로 열리지 않는다 — Spring Cloud Gateway 4.x 부터
 * {@code management.endpoint.gateway.enabled} 기본값이 {@code false},
 * {@code management.endpoint.gateway.access} 기본값이 {@code none} 이기 때문이다.
 * <b>즉 지금 닫혀 있는 이유는 설정이 막아서가 아니라 라이브러리 기본값 덕분이다.</b>
 * 누가 그 기본값을 켜는 순간 라우트 목록과 {@code refresh}(쓰기)가 인증 없이 열린다 —
 * 이 모듈에는 security 의존성이 없다. 그 변경을 여기서 잡는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayActuatorExposureTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("헬스체크는 열려 있다 — 로드밸런서가 봐야 한다")
    void healthIsExposed() {
        webTestClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("라우트 목록은 외부 포트에 노출되지 않는다")
    void gatewayRouteListingIsNotExposed() {
        // 열리면 내부 서비스 호스트·포트와, 게이트웨이가 무엇을 막고 있는지
        // (catalog 의 Method=GET 술어)가 그대로 읽힌다.
        webTestClient.get().uri("/actuator/gateway/routes")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("라우트 갱신은 외부에서 호출할 수 없다 — 조회가 아니라 쓰기다")
    void gatewayRefreshIsNotExposed() {
        webTestClient.post().uri("/actuator/gateway/refresh")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("환경변수·빈 목록도 닫혀 있다 — 노출 목록에 없는 것은 실제로 없다")
    void otherManagementEndpointsAreNotExposed() {
        // exposure.include 가 실제로 통제로 동작하는지 확인한다.
        // 위 gateway 엔드포인트는 라이브러리 기본값 덕에 닫혀 있어서, 이 성질을 따로 봐야 한다.
        webTestClient.get().uri("/actuator/env").exchange().expectStatus().isNotFound();
        webTestClient.get().uri("/actuator/beans").exchange().expectStatus().isNotFound();
    }
}
