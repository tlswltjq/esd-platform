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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.util.PropertyPlaceholderHelper;
import org.springframework.util.SystemPropertyUtils;

/**
 * [D-053] Gateway 연결 풀 유휴 상한 &lt; 하류 서비스가 유휴 연결을 닫는 시간.
 *
 * <p>이 부등호가 뒤집히면 하류 Tomcat이 연결을 닫는 바로 그 순간 Gateway가 그 연결을 골라 요청을 보내고,
 * 요청이 응답 전에 끊겨 500이 된다. 계약은 <b>Gateway 한 파일과 하류 열 파일에 걸쳐 있어</b> 어느 앱 모듈에도 넣지 않는다
 * ({@link DeploymentShutdownContractTest}와 같은 이유). 동작은 {@code apps/gateway}의 {@code GatewayIdleConnectionTest}가 본다.
 */
class GatewayIdleTimeoutContractTest {

    /** WebFlux 프록시(결정 223): 내부 API 게이트웨이와 웹 BFF. 하류는 나머지 서블릿 앱이다. */
    private static final List<String> PROXIES = List.of("gateway", "web-bff");
    private static final String POOL_MAX_IDLE = "spring.cloud.gateway.server.webflux.httpclient.pool.max-idle-time";
    /** Tomcat {@code connectionTimeout} 기본값({@code org.apache.coyote.http11.Constants.DEFAULT_CONNECTION_TIMEOUT}). */
    private static final Duration TOMCAT_DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Spring과 같은 자리표시자 해석기. 배포 값은 모르므로 모든 이름을 비워 두고 <b>기본값</b>으로 판정한다(중첩 기본값 포함,
     * PR #229 R2). 기본값까지 없으면 해석기가 실패한다. 접두·접미·구분·이스케이프 문자는 Spring Boot 바인딩과 같은
     * {@link SystemPropertyUtils} 상수를 쓴다(R3: {@code \:}는 구분자가 아니라 이름의 일부다).
     */
    private static final PropertyPlaceholderHelper PLACEHOLDERS = new PropertyPlaceholderHelper(
            SystemPropertyUtils.PLACEHOLDER_PREFIX, SystemPropertyUtils.PLACEHOLDER_SUFFIX,
            SystemPropertyUtils.VALUE_SEPARATOR, SystemPropertyUtils.ESCAPE_CHARACTER, false);

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
        String text;
        try {
            text = PLACEHOLDERS.replacePlaceholders(raw.toString().trim(), name -> null);
        } catch (IllegalArgumentException unresolved) {
            throw new AssertionError(key + "=" + raw + " — 기본값 없는 자리표시자라 배포 값을 알 수 없다", unresolved);
        }
        return DurationStyle.detectAndParse(text.trim(), ChronoUnit.MILLIS);
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
        assertThat(springDuration("k", "${TIMEOUT:${FALLBACK:45s}}")).isEqualTo(Duration.ofSeconds(45));
        assertThat(springDuration("k", "PT1M")).isEqualTo(Duration.ofMinutes(1));
        assertThat(springDuration("k", null)).isNull();
        assertThatThrownBy(() -> springDuration("k", "${TIMEOUT}"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("기본값 없는 자리표시자");
        assertThatThrownBy(() -> springDuration("k", "${TIMEOUT:${FALLBACK}}"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("기본값 없는 자리표시자");
        assertThatThrownBy(() -> springDuration("k", "${TIMEOUT:${FALLBACK\\:45s}}"))
                .as("이스케이프된 콜론은 기본값 구분자가 아니다")
                .isInstanceOf(AssertionError.class).hasMessageContaining("기본값 없는 자리표시자");
    }

    @Test
    @DisplayName("[D-053] 프록시(API 게이트웨이·웹 BFF)의 연결 풀 유휴 상한은 모든 서블릿 하류 서비스의 keep-alive 유휴 닫기보다 짧다")
    void proxyPoolIdleLimitIsShorterThanEveryDownstreamKeepAlive() {
        List<Path> apps = appDirectories();
        List<Path> proxies = apps.stream().filter(app -> PROXIES.contains(app.getFileName().toString())).toList();
        assertThat(proxies).as("프록시 둘(결정 223)").hasSize(PROXIES.size());
        List<Path> downstream = apps.stream().filter(app -> !proxies.contains(app)).toList();
        assertThat(downstream).as("하류 서비스가 0개면 이 계약은 아무것도 지키지 않는다").hasSizeGreaterThanOrEqualTo(10);

        for (Path proxy : proxies) {
            Duration maxIdle = springDuration(POOL_MAX_IDLE, at(yamlOf(proxy), POOL_MAX_IDLE));
            assertThat(maxIdle).as("%s의 %s가 없으면 상한이 없다(기본값 null) — 하류가 닫은 연결을 다시 쓴다",
                    proxy.getFileName(), POOL_MAX_IDLE).isNotNull();
            for (Path app : downstream) {
                Duration keepAlive = keepAliveOf(yamlOf(app));
                assertThat(maxIdle)
                        .as("%s의 keep-alive 유휴 닫기(%s)보다 %s 유휴 상한이 짧아야 한다", app.getFileName(), keepAlive, proxy.getFileName())
                        .isLessThan(keepAlive);
            }
        }
    }
}
