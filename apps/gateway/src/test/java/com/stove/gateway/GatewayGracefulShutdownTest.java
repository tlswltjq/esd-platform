package com.stove.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * 시나리오 R-01b — <b>종료 신호를 받았을 때 진행 중이던 요청이 실제로 완주하는가.</b>
 *
 * <h2>왜 이 테스트가 따로 필요한가</h2>
 *
 * <p>{@code DeploymentShutdownContractTest} 는 <b>설정</b>을 본다 — 열 앱이 graceful 인지,
 * 컨테이너 유예가 종료 단계보다 긴지. 그것으로 D-034·D-035 를 잡았지만,
 * <b>거기서 멈추면 D-035 가 지적한 것과 같은 자리에 서게 된다.</b>
 *
 * <p>D-035 의 문장이 이것이었다: <i>"장치가 있다 와 장치가 그 자리를 지킨다 는 다르다 —
 * 넷 다 장치를 켠 것으로 확인을 대신했다."</i> 그런데 그 결함을 고친 뒤에도
 * <b>진행 중이던 요청이 종료를 넘겨 완주하는 것을 이 저장소에서 본 사람이 없었다.</b>
 * 부등호가 성립해도 스프링 쪽 배선이 어긋나 있으면 요청은 그대로 끊긴다.
 *
 * <p>그래서 여기서는 설정을 읽지 않는다. <b>요청을 하나 띄워 두고 컨텍스트를 닫는다.</b>
 * 응답이 돌아오면 graceful 이 동작한 것이고, 돌아오지 않으면 적혀만 있는 것이다.
 *
 * <h2>왜 게이트웨이에서 재는가</h2>
 *
 * <p>둘이다. <b>실제로 빠져 있던 곳이고</b>(D-034), <b>혼자 WebFlux 다.</b>
 * 나머지 아홉은 서블릿이라 종료 경로의 구현이 다르다 — 아홉이 되는 것을 확인해도
 * 게이트웨이가 되는지는 알 수 없고, 하필 모든 트래픽이 그 문을 지난다.
 *
 * <p>인프라가 필요 없으므로 {@code src/test} 에 둔다. 배포 계약을 확인하는 데
 * 컨테이너 스택을 요구할 이유가 없다.
 *
 * <h2>왜 컨텍스트를 직접 띄우는가 — {@code @SpringBootTest} 가 아니다</h2>
 *
 * <p>이 테스트의 판정은 <b>컨텍스트를 닫는 것</b>이다. 그런데 {@code @SpringBootTest} 가 준
 * 컨텍스트를 닫으면 프레임워크가 그 뒤에 죽은 컨텍스트를 만지고
 * {@code ApplicationContext ... is not active} 로 깨진다 — 실제로 그렇게 한 번 빨개졌다.
 * 캐시는 프레임워크의 것이므로 <b>닫는 것이 판정인 테스트는 자기 컨텍스트를 가져야 한다.</b>
 *
 * <p>그리고 이 방식이 재려던 것에 더 가깝다. {@code SpringApplicationBuilder} 로 띄우면
 * 게이트웨이의 {@code application.yml} 이 그대로 읽히므로 {@code server.shutdown} 과
 * {@code spring.lifecycle.timeout-per-shutdown-phase} 를 <b>운영과 같은 값으로</b> 태운다.
 * {@code webServer.shutDownGracefully()} 를 직접 부르는 편법을 쓰지 않는 이유도 이것이다 —
 * 그건 "네티가 graceful 을 할 수 있는가" 이고, 물어야 하는 것은 <b>"이 앱이 그렇게 설정돼 있는가"</b> 다.
 */
@DisplayName("종료 — 진행 중이던 요청")
class GatewayGracefulShutdownTest {

    private static final String PROBE = "/__shutdown-probe__";
    private static final String DONE = "완주";

    /**
     * 핸들러가 응답을 붙들고 있는 시간.
     *
     * <p><b>이 값이 이 테스트가 무언가를 재는지 여부를 정한다.</b> 처음에 2초로 잡았더니
     * {@code server.shutdown: immediate} 로 바꿔도 <b>초록이었다</b> — 리액터 네티의
     * {@code disposeNow} 기본 유예가 3초라, graceful 이 아니어도 2초짜리 요청은 그 안에 끝난다.
     * <b>기본값이 차이를 가려 테스트가 공허해진 것이다.</b>
     *
     * <p>그래서 그 3초 위로 올린다. 8초면 두 경우가 갈린다 —
     * graceful 은 종료 단계 타임아웃(30초)까지 기다리므로 완주하고,
     * immediate 는 3초에 커넥션을 끊어 클라이언트가 오류를 받는다.
     * 실제로 그렇게 확인했다: immediate 에서 빨갛고 graceful 에서 초록이다.
     *
     * <p>회차가 8초 길어지는 것이 대가다. <b>차이를 만들지 못하는 빠른 테스트보다
     * 차이를 만드는 느린 테스트가 낫다.</b>
     */
    private static final Duration HELD = Duration.ofSeconds(8);

    /**
     * 요청이 <b>서버에 도달했다</b>는 신호.
     *
     * <p>이게 없으면 종료를 언제 걸어야 할지 알 수 없다. 고정 sleep 으로 대신하면
     * 짧을 때 요청이 도달하기 전에 닫아서 <b>'진행 중인 요청' 자체가 없는 회차가 통과한다</b> —
     * 이 저장소가 반복해서 막아 온 "공허한 초록" 이다.
     */
    private static final CountDownLatch ARRIVED = new CountDownLatch(1);

    /** 이 라우트는 테스트 컨텍스트에만 있다. "진행 중" 을 만들려고 운영 코드를 늘리지 않는다. */
    @Configuration(proxyBeanMethods = false)
    static class SlowEndpoint {

        @Bean
        RouterFunction<ServerResponse> shutdownProbe() {
            return RouterFunctions.route(RequestPredicates.GET(PROBE), request -> {
                ARRIVED.countDown();
                return ServerResponse.ok().body(Mono.just(DONE).delayElement(HELD), String.class);
            });
        }
    }

    @Test
    @DisplayName("종료 신호를 받아도 진행 중이던 요청은 끊기지 않고 완주한다")
    void inFlightRequestSurvivesShutdown() throws Exception {
        ConfigurableApplicationContext app = new SpringApplicationBuilder(
                GatewayApplication.class, SlowEndpoint.class)
                .web(WebApplicationType.REACTIVE)
                .properties("server.port=0")
                .run();

        int port = ((WebServerApplicationContext) app).getWebServer().getPort();
        ExecutorService client = Executors.newSingleThreadExecutor();
        try {
            Future<String> inFlight = client.submit(() -> RestClient.create()
                    .get()
                    .uri("http://127.0.0.1:%d%s".formatted(port, PROBE))
                    .retrieve()
                    .body(String.class));

            assertThat(ARRIVED.await(20, TimeUnit.SECONDS))
                    .as("요청이 서버에 도달하기 전에 닫으면 '진행 중인 요청' 이 없어 "
                            + "아래 판정이 아무것도 검사하지 않는다")
                    .isTrue();

            // 배포가 SIGTERM 을 보내는 순간과 같은 자리다 —
            // 스프링은 여기서 server.shutdown 설정에 따라 웹 서버를 내린다.
            app.close();

            assertThat(inFlight.get(40, TimeUnit.SECONDS))
                    .as("""
                            응답이 오지 않으면 종료가 진행 중이던 요청을 끊은 것이다.
                            server.shutdown 이 graceful 인지,
                            spring.lifecycle.timeout-per-shutdown-phase 가 핸들러 시간보다 긴지 순서로 본다.""")
                    .isEqualTo(DONE);
        } finally {
            client.shutdownNow();
            if (app.isActive()) {
                app.close();
            }
        }
    }
}
