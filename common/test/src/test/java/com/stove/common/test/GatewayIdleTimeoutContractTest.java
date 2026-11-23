package com.stove.common.test;

import static com.stove.common.test.DeploymentFiles.appDirectories;
import static com.stove.common.test.DeploymentFiles.at;
import static com.stove.common.test.DeploymentFiles.loadYaml;
import static com.stove.common.test.DeploymentFiles.parseDuration;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    private static Map<String, Object> yamlOf(Path app) {
        return loadYaml(app.resolve("src/main/resources/application.yml"));
    }

    /** keep-alive 유휴 닫기 시간. 따로 없으면 Tomcat은 connectionTimeout을 쓴다. */
    private static Duration keepAliveOf(Map<String, Object> yaml) {
        Duration keepAlive = parseDuration(at(yaml, "server.tomcat.keep-alive-timeout"));
        if (keepAlive != null) {
            return keepAlive;
        }
        Duration connection = parseDuration(at(yaml, "server.tomcat.connection-timeout"));
        return connection != null ? connection : TOMCAT_DEFAULT_CONNECTION_TIMEOUT;
    }

    @Test
    @DisplayName("[D-053] Gateway 연결 풀 유휴 상한은 모든 하류 서비스의 keep-alive 유휴 닫기보다 짧다")
    void gatewayPoolIdleLimitIsShorterThanEveryDownstreamKeepAlive() {
        List<Path> apps = appDirectories();
        Path gateway = apps.stream().filter(app -> app.getFileName().toString().equals(GATEWAY)).findFirst().orElseThrow();
        List<Path> downstream = apps.stream().filter(app -> !app.equals(gateway)).toList();
        assertThat(downstream).as("하류 서비스가 0개면 이 계약은 아무것도 지키지 않는다").hasSizeGreaterThanOrEqualTo(10);

        Duration maxIdle = parseDuration(at(yamlOf(gateway), POOL_MAX_IDLE));
        assertThat(maxIdle).as("%s가 없으면 상한이 없다(기본값 null) — 하류가 닫은 연결을 다시 쓴다", POOL_MAX_IDLE).isNotNull();
        for (Path app : downstream) {
            Duration keepAlive = keepAliveOf(yamlOf(app));
            assertThat(maxIdle)
                    .as("%s의 keep-alive 유휴 닫기(%s)보다 Gateway 유휴 상한이 짧아야 한다", app.getFileName(), keepAlive)
                    .isLessThan(keepAlive);
        }
    }
}
