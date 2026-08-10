package com.stove.e2e;

/**
 * 검증 대상 스택 — 어디를 두드릴 것인가.
 *
 * <h2>왜 루프백인가</h2>
 *
 * <p>CI compose 는 호스트 포트를 열지 않는다. 편의가 아니라 보안 결정이고(공개 IP +
 * 인증 없는 Elasticsearch·Grafana·MinIO), 근거는 {@code docker-compose.ci.yml} 에 적혀 있다.
 * 그런데 e2e 는 스택 <b>밖의</b> JVM 이라 컨테이너 네트워크 이름({@code http://gateway:8080})으로는
 * 닿을 수 없다 — 셸은 같은 네트워크에 curl 컨테이너를 띄워 그 문제를 피했다.
 *
 * <p>위험한 것은 포트를 여는 행위가 아니라 {@code 0.0.0.0} 에 붙이는 것이었다. 그래서
 * {@code 127.0.0.1:1808X} 로만 연다({@code docker-compose.apps.e2e.yml}) — 원래 근거를
 * 뒤집지 않고 풀린다. 결정은 {@code docs/decisions.md} 21번.
 *
 * <h2>왜 게이트웨이인가</h2>
 *
 * <p>거의 모든 호출이 게이트웨이를 거친다. <b>실제 경로가 그렇기 때문이다</b> — PG 콜백도
 * 클라이언트 요청도 창작자의 스튜디오 호출도 밖에서 온다. 셸은 서비스를 직접 불렀고,
 * 그래서 라우팅 표가 깨져도 인수 45건은 전부 초록이었다.
 *
 * <p>직접 부르는 것은 하나뿐이다 — {@code POST /api/v1/products/{id}/sale-open}.
 * catalog 의 공개 라우트는 {@code Method=GET} 이라 이 운영 호출은 밖에서 닿지 않는 것이 정상이다
 * (게이트웨이 차단은 배포 게이트가 따로 확인한다 — {@code scripts/stack-wait.sh} 4장).
 * <b>이 목록이 늘어나면 그 자체가 검토 대상이다.</b>
 */
final class Stove {

    /** 유저·창작자·PG 가 두드리는 문. */
    static final E2eClient gateway = new E2eClient(url("gateway", "http://127.0.0.1:18080"));

    /** 게이트웨이에 라우팅되지 않는 운영 호출 전용. */
    static final E2eClient catalog = new E2eClient(url("catalog", "http://127.0.0.1:18081"));

    private Stove() {
    }

    /**
     * 빈 문자열을 '없음' 으로 읽는다.
     *
     * <p>그레이들이 미설정 값을 빈 문자열로 넘기기 때문이다({@code e2e/build.gradle} 의
     * {@code getOrElse('')}). {@code null} 검사만 하면 스킴도 호스트도 없는 주소로 붙으러 간다.
     */
    private static String url(String name, String fallback) {
        String value = System.getProperty("stove.e2e." + name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
