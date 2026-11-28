package com.stove.e2e;


/**
 * 검증 대상 스택 — 어디를 두드릴 것인가.
 *
 * <h2>왜 루프백인가</h2>
 *
 * <p>기본 Compose는 호스트 포트를 열지 않는다. CI에서도 같은 정의를 사용한다.
 * 로컬·E2E는 local 오버라이드로 루프백 포트를 연다. E2E는 스택 <b>밖의</b> JVM 이라 컨테이너 네트워크 이름({@code
 * http://gateway:8080})으로는 닿을 수 없다 — 셸은 같은 네트워크에 curl 컨테이너를 띄워 그 문제를 피했다.
 *
 * <p>위험한 것은 포트를 여는 행위가 아니라 {@code 0.0.0.0} 에 붙이는 것이었다. 그래서 {@code 127.0.0.1} + 오프셋 {@value
 * #PORT_OFFSET} 으로만 연다. 결정은 {@code docs/decisions.md} 21번.
 *
 * <p><b>오프셋이 규칙이라 노브가 하나다.</b> 주소를 서비스마다 두면 아홉 개를 관리하게 된다 — {@code -Dstove.e2e.host=} 로 호스트만 덮으면
 * 나머지는 계산된다.
 *
 * <h2>왜 게이트웨이인가</h2>
 *
 * <p>거의 모든 호출이 게이트웨이를 거친다. <b>실제 경로가 그렇기 때문이다</b> — PG 콜백도 클라이언트 요청도 창작자의 스튜디오 호출도 밖에서 온다. 셸은 서비스를
 * 직접 불렀고, 그래서 라우팅 표가 깨져도 인수 판정은 전부 초록이었다.
 *
 * <p>그리고 이것이 트레이스 판정의 전제이기도 하다. 결제 콜백을 게이트웨이로 넣어야 하나의 traceId 가 <b>gateway → payment →
 * license·order·settlement → download</b> 로 여섯 서비스에 걸린다. 직접 부르면 다섯이다.
 *
 * <p>직접 부르는 것은 하나뿐이다 — {@code POST /api/v1/products/{id}/sale-open}. catalog 의 공개 라우트는 {@code
 * Method=GET} 이라 이 운영 호출은 밖에서 닿지 않는 것이 정상이다. <b>이 목록이 늘어나면 그 자체가 검토 대상이다.</b>
 */
final class Stove {

    /** 컨테이너 포트에 이만큼 더한 것이 호스트 포트다. traefik 이 잡던 8080 과도 겹치지 않는다. */
    private static final int PORT_OFFSET = Integer.getInteger("stove.e2e.port-offset", 10_000);

    private static final String HOST = property("host", "http://127.0.0.1");

    /** {@link #gateway} 의 주소. BFF 로그인은 브라우저처럼 이 주소의 쿠키 저장소를 쓴다. */
    static String gatewayBaseUrl() {
        return HOST + ":" + (8080 + PORT_OFFSET);
    }

    /** 유저·창작자·PG 가 두드리는 문. */
    static final E2eClient gateway = at(8080);

    static final E2eClient auth = at(8091);

    /** 게이트웨이에 라우팅되지 않는 운영 호출 전용. */
    static final E2eClient catalog = at(8081);

    /** 트레이스 조회 API. {@code docker-compose.local.yml} 이 루프백 포트를 연다. */
    static final E2eClient tempo = at(3200);

    /** Gateway에 라우팅되지 않는 내부 API 전용(결제 접수 계약 픽스처가 Payment처럼 부른다). */
    static final E2eClient order = at(8082);

    private Stove() {}

    private static E2eClient at(int containerPort) {
        return new E2eClient("%s:%d".formatted(HOST, containerPort + PORT_OFFSET));
    }

    /**
     * 빈 문자열을 '없음' 으로 읽는다.
     *
     * <p>그레이들이 미설정 값을 빈 문자열로 넘기기 때문이다({@code e2e/build.gradle} 의 {@code getOrElse('')}). {@code
     * null} 검사만 하면 스킴도 호스트도 없는 주소로 붙으러 간다.
     */
    private static String property(String name, String fallback) {
        String value = System.getProperty("stove.e2e." + name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
