package com.stove.common.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 시나리오 R-01 — <b>배포로 프로세스가 내려갈 때 진행 중이던 요청은 어떻게 되는가.</b>
 *
 * <p>이 저장소에서 서버가 중단되는 가장 흔한 사건은 장애가 아니라 <b>배포</b>다.
 * {@code docker compose -f docker-compose.apps.yml up -d --build} 한 줄이 앱 10종을 갈아끼우고,
 * 그 순간 각 컨테이너는 SIGTERM 을 받는다. 그때 진행 중이던 요청이 끊기면
 * 사용자에게는 장애와 구분되지 않는다 — 그리고 결제 콜백이 그 요청일 수 있다.
 *
 * <h2>왜 파일을 읽는가</h2>
 *
 * <p>이 계약은 <b>앱 하나의 성질이 아니라 앱 집합의 성질</b>이라 어느 앱 모듈 안에도 들어가지 않는다.
 * 한 앱에 두면 나머지 아홉은 그 앱이 테스트를 지우는 순간 아무 신호 없이 보호를 잃는다 —
 * {@code OutboxPendingQueryTest} 가 "9개 서비스가 의존하는 쿼리의 회귀 방어선이 앱 하나에
 * 인질로 잡혀 있었다" 며 이 모듈 쪽으로 옮겨온 것과 같은 이유다.
 *
 * <p>그리고 이 계약은 <b>두 파일에 걸쳐 있다.</b> 한쪽만 봐서는 성립 여부를 말할 수 없다.
 *
 * <h2>지키는 것 둘</h2>
 *
 * <ol>
 *   <li><b>앱 전부가 graceful 이다.</b> 하나라도 빠지면 그 서비스만 배포 때마다 요청을 끊는다.</li>
 *   <li><b>컨테이너 종료 유예 &gt; 스프링 종료 단계 타임아웃.</b> 이 부등호가 뒤집히면
 *       설정은 graceful 인데 실제로는 잘린다 — <b>가장 나쁜 종류의 실패다.</b>
 *       설정 파일에는 안전 장치가 적혀 있으므로 아무도 다시 보지 않는데,
 *       도커가 유예 시간이 지나면 SIGKILL 을 보내 진행 중이던 요청을 그 자리에서 끊는다.
 *       도커의 기본 유예는 10초, 스프링의 기본 종료 단계 타임아웃은 30초다 —
 *       <b>둘 다 기본값으로 두면 이 부등호는 항상 거짓이다.</b></li>
 * </ol>
 */
class DeploymentShutdownContractTest {

    private static final String APPS_COMPOSE = "docker-compose.apps.yml";

    /** 스프링 기본값. {@code spring.lifecycle.timeout-per-shutdown-phase} 를 안 적으면 이 값이다. */
    private static final Duration SPRING_DEFAULT_PHASE_TIMEOUT = Duration.ofSeconds(30);

    /** 도커 기본값. compose 에 {@code stop_grace_period} 를 안 적으면 이 값이다. */
    private static final Duration DOCKER_DEFAULT_GRACE = Duration.ofSeconds(10);

    /**
     * 리포 루트를 찾는다.
     *
     * <p>테스트의 작업 디렉터리는 모듈 디렉터리라({@code common/test}) 상대 경로를 박으면
     * 모듈을 옮기는 순간 조용히 깨진다. {@code settings.gradle} 이 있는 곳이 루트라는 사실만 쓴다.
     */
    private static Path repoRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && Files.notExists(candidate.resolve("settings.gradle"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("settings.gradle 을 찾지 못했다 — 리포 루트를 알 수 없다");
        }
        return candidate;
    }

    private static List<Path> appDirectories() {
        try (Stream<Path> apps = Files.list(repoRoot().resolve("apps"))) {
            return apps.filter(Files::isDirectory)
                    .filter(dir -> Files.exists(dir.resolve("src/main/resources/application.yml")))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("apps/ 를 읽을 수 없다", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            Map<String, Object> loaded = new Yaml().load(in);
            return loaded == null ? Map.of() : loaded;
        } catch (IOException e) {
            throw new IllegalStateException("YAML 을 읽을 수 없다: " + path, e);
        }
    }

    /** {@code a.b.c} 를 중첩 맵에서 꺼낸다. 없으면 null. */
    @SuppressWarnings("unchecked")
    private static Object at(Map<String, Object> yaml, String dottedPath) {
        Object current = yaml;
        for (String key : dottedPath.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(key);
        }
        return current;
    }

    /**
     * {@code 30s} · {@code 2m} · {@code 45} 를 읽는다.
     *
     * <p>compose 와 스프링이 쓰는 표기가 다르므로 양쪽을 같은 자리에서 받는다.
     * 단위가 없으면 초로 읽는다(compose 의 규약이다).
     */
    private static Duration parseDuration(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.toString().trim();
        if (text.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(text.substring(0, text.length() - 2).trim()));
        }
        if (text.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(text.substring(0, text.length() - 1).trim()));
        }
        if (text.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(text.substring(0, text.length() - 1).trim()));
        }
        return Duration.ofSeconds(Long.parseLong(text));
    }

    // ── 1. 앱 전부가 graceful 인가 ────────────────────────────────────

    @Test
    @DisplayName("앱 전부가 graceful shutdown 을 켠다 — 배포는 이 시스템에서 가장 잦은 '서버 중단'이다")
    void everyAppShutsDownGracefully() {
        List<String> notGraceful = new ArrayList<>();

        for (Path app : appDirectories()) {
            Map<String, Object> yaml = loadYaml(app.resolve("src/main/resources/application.yml"));
            Object shutdown = at(yaml, "server.shutdown");
            if (!"graceful".equals(shutdown)) {
                notGraceful.add("%s (server.shutdown=%s)".formatted(app.getFileName(), shutdown));
            }
        }

        assertThat(notGraceful)
                .as("""
                        graceful 이 아닌 앱은 배포마다 진행 중이던 요청을 그 자리에서 끊는다.
                        게이트웨이라면 그 요청이 PG 콜백일 수 있다 — 하류는 아무 일도 없었던 것처럼 보이고
                        PG 쪽에는 실패로 남는다.
                          고치는 법: 해당 앱의 application.yml 에 server.shutdown: graceful""")
                .isEmpty();
    }

    // ── 2. 유예가 종료 단계보다 긴가 ──────────────────────────────────

    /**
     * 컨테이너가 SIGKILL 을 보내기까지 주는 시간이, 스프링이 요청을 마치는 데 쓰는 시간보다 길어야 한다.
     *
     * <p><b>이 판정이 이 클래스의 존재 이유다.</b> 위의 첫 판정만 있으면
     * "graceful 이라고 적혀 있으니 됐다" 로 끝나는데, 적혀 있어도 도커가 10초에 끊으면
     * 30초를 기다릴 작정이던 스프링은 <b>요청을 든 채로 죽는다.</b>
     * 설정과 실제가 어긋나는 실패는 로그에도 지표에도 남지 않는다.
     */
    @Test
    @DisplayName("컨테이너 종료 유예가 스프링 종료 단계보다 길다 — 아니면 graceful 은 적혀만 있는 것이다")
    void containerGraceOutlastsSpringShutdownPhase() {
        Map<String, Object> compose = loadYaml(repoRoot().resolve(APPS_COMPOSE));
        Map<String, Object> services = asMap(compose.get("services"));

        List<String> truncated = new ArrayList<>();

        for (Path app : appDirectories()) {
            String name = app.getFileName().toString();
            Map<String, Object> service = asMap(services.get(name));
            if (service.isEmpty()) {
                continue;   // compose 에 없는 앱은 이 계약의 대상이 아니다
            }

            Duration grace = parseDuration(service.get("stop_grace_period"));
            Duration phase = parseDuration(at(
                    loadYaml(app.resolve("src/main/resources/application.yml")),
                    "spring.lifecycle.timeout-per-shutdown-phase"));

            Duration effectiveGrace = grace == null ? DOCKER_DEFAULT_GRACE : grace;
            Duration effectivePhase = phase == null ? SPRING_DEFAULT_PHASE_TIMEOUT : phase;

            if (effectiveGrace.compareTo(effectivePhase) <= 0) {
                truncated.add("%s (유예 %s%s ≤ 종료단계 %s%s)".formatted(
                        name,
                        effectiveGrace, grace == null ? " [도커 기본값]" : "",
                        effectivePhase, phase == null ? " [스프링 기본값]" : ""));
            }
        }

        assertThat(truncated)
                .as("""
                        유예가 종료 단계보다 짧으면 SIGKILL 이 먼저 도착한다 — graceful 이 잘린다.
                        둘 다 기본값이면(도커 10초 · 스프링 30초) 이 부등호는 항상 거짓이므로,
                        **양쪽을 명시해야 성립한다.**
                          고치는 법: %s 의 서비스에 stop_grace_period,
                                     application.yml 에 spring.lifecycle.timeout-per-shutdown-phase"""
                        .formatted(APPS_COMPOSE))
                .isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return raw instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
    }

    /**
     * 앱 목록을 세어 둔다.
     *
     * <p>위 두 판정은 <b>목록이 비면 조용히 통과한다.</b> 경로 규칙이 바뀌거나 작업
     * 디렉터리가 달라지면 0개를 훑고 초록이 되는데, 화면에서는 통과와 구분되지 않는다 —
     * 이 저장소가 {@code EXPECTED_CHECKS} 와 e2e 실행 건수 보고로 반복해서 막아 온 실패 모드다.
     */
    @Test
    @DisplayName("검사 대상이 실제로 잡혔다 — 0개를 훑고 초록이 되지 않는다")
    void theContractActuallyHasSubjects() {
        assertThat(appDirectories())
                .as("apps/ 아래에서 application.yml 을 가진 앱을 하나도 못 찾았다면 "
                        + "위 두 판정은 아무것도 검사하지 않은 것이다")
                .hasSizeGreaterThanOrEqualTo(9);
    }
}
