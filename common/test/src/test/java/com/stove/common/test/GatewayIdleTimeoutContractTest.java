package com.stove.common.test;

import static com.stove.common.test.DeploymentFiles.appDirectories;
import static com.stove.common.test.DeploymentFiles.at;
import static com.stove.common.test.DeploymentFiles.loadYaml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;

/**
 * [D-053] Gateway 연결 풀 유휴 상한 &lt; 하류 서비스가 유휴 연결을 닫는 시간.
 *
 * <p>이 부등호가 뒤집히면 하류 Tomcat이 연결을 닫는 바로 그 순간 Gateway가 그 연결을 골라 요청을 보내고,
 * 요청이 응답 전에 끊겨 500이 된다. 계약은 <b>Gateway 한 파일과 하류 열 파일에 걸쳐 있어</b> 어느 앱 모듈에도 넣지 않는다
 * ({@link DeploymentShutdownContractTest}와 같은 이유). 동작은 {@code apps/gateway}의 {@code GatewayIdleConnectionTest}가 본다.
 */
class GatewayIdleTimeoutContractTest {

    private static final String GATEWAY = "gateway";
    private static final String POOL_MAX_IDLE = "spring.cloud.gateway.server.webflux.httpclient.pool.max-idle-time";
    /** Tomcat {@code connectionTimeout} 기본값({@code org.apache.coyote.http11.Constants.DEFAULT_CONNECTION_TIMEOUT}). */
    private static final Duration TOMCAT_DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(60);

    /** {@code ${NAME:기본값}} — 배포가 값을 주지 않을 때 쓰이는 기본값으로 판정한다. */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{[^:}]+:(.*)}$");

    private static Map<String, Object> yamlOf(Path app) {
        return loadYaml(app.resolve("src/main/resources/application.yml"));
    }

    /**
     * Spring이 바인딩하는 것과 같게 읽는다 — 단위가 없으면 <b>밀리초</b>다(PR #229 R1). Compose용
     * {@link DeploymentFiles#parseDuration}은 단위 없는 값을 초로 읽어 여기에 쓰면 {@code 20000}을 20,000초로 본다.
     */
    static Duration springDuration(String key, Object raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.toString().trim();
        Matcher placeholder = PLACEHOLDER.matcher(text);
        if (placeholder.matches()) {
            text = placeholder.group(1).trim();
        } else if (text.contains("${")) {
            throw new AssertionError(key + "=" + raw + " — 기본값 없는 자리표시자라 배포 값을 알 수 없다");
        }
        return DurationStyle.detectAndParse(text, ChronoUnit.MILLIS);
    }

    /** keep-alive 유휴 닫기 시간. 따로 없으면 Tomcat은 connectionTimeout을 쓴다. */
    private static Duration keepAliveOf(Map<String, Object> yaml) {
        Duration keepAlive = springDuration("server.tomcat.keep-alive-timeout", at(yaml, "server.tomcat.keep-alive-timeout"));
        if (keepAlive != null) {
            return keepAlive;
        }
        Duration connection = springDuration("server.tomcat.connection-timeout", at(yaml, "server.tomcat.connection-timeout"));
        return connection != null ? connection : TOMCAT_DEFAULT_CONNECTION_TIMEOUT;
    }

    @Test
    @DisplayName("설정 값은 Spring처럼 읽는다 — 단위 없으면 밀리초, 자리표시자는 기본값")
    void durationsAreReadTheWaySpringBindsThem() {
        assertThat(springDuration("k", 20000)).isEqualTo(Duration.ofSeconds(20));
        assertThat(springDuration("k", "30s")).isEqualTo(Duration.ofSeconds(30));
        assertThat(springDuration("k", "${TIMEOUT:45s}")).isEqualTo(Duration.ofSeconds(45));
        assertThat(springDuration("k", "PT1M")).isEqualTo(Duration.ofMinutes(1));
        assertThat(springDuration("k", null)).isNull();
        assertThatThrownBy(() -> springDuration("k", "${TIMEOUT}"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("기본값 없는 자리표시자");
    }

    @Test
    @DisplayName("[D-053] Gateway 연결 풀 유휴 상한은 모든 하류 서비스의 keep-alive 유휴 닫기보다 짧다")
    void gatewayPoolIdleLimitIsShorterThanEveryDownstreamKeepAlive() {
        List<Path> apps = appDirectories();
        Path gateway = apps.stream().filter(app -> app.getFileName().toString().equals(GATEWAY)).findFirst().orElseThrow();
        List<Path> downstream = apps.stream().filter(app -> !app.equals(gateway)).toList();
        assertThat(downstream).as("하류 서비스가 0개면 이 계약은 아무것도 지키지 않는다").hasSizeGreaterThanOrEqualTo(10);

        Duration maxIdle = springDuration(POOL_MAX_IDLE, at(yamlOf(gateway), POOL_MAX_IDLE));
        assertThat(maxIdle).as("%s가 없으면 상한이 없다(기본값 null) — 하류가 닫은 연결을 다시 쓴다", POOL_MAX_IDLE).isNotNull();
        for (Path app : downstream) {
            Duration keepAlive = keepAliveOf(yamlOf(app));
            assertThat(maxIdle)
                    .as("%s의 keep-alive 유휴 닫기(%s)보다 Gateway 유휴 상한이 짧아야 한다", app.getFileName(), keepAlive)
                    .isLessThan(keepAlive);
        }
    }
}
