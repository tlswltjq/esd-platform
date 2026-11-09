package com.stove.common.test;

import static com.stove.common.test.DeploymentFiles.APPS_COMPOSE;
import static com.stove.common.test.DeploymentFiles.appDirectories;
import static com.stove.common.test.DeploymentFiles.asMap;
import static com.stove.common.test.DeploymentFiles.at;
import static com.stove.common.test.DeploymentFiles.loadYaml;
import static com.stove.common.test.DeploymentFiles.parseDuration;
import static com.stove.common.test.DeploymentFiles.repoRoot;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 여러 앱이 함께 뜰 때 DB 첫 연결이 늦어도 부팅이 실패하지 않는가(결정 136).
 *
 * <p>DB를 쓰는 앱은 부팅 중 Flyway가 Hikari에서 첫 연결을 받는다. Hikari 기본값
 * ({@code initialization-fail-timeout} 1ms)은 그 연결을 한 번만 시도하므로, 여러 JVM이 함께 떠
 * 첫 연결이 {@code connection-timeout}(3초) 안에 끝나지 않으면 기동이 바로 실패한다.
 * 결정 136은 부팅 때만 60초 동안 다시 시도하고, 실행 중 요청의 3초 기한은 그대로 둔다.
 *
 * <p>지키는 것 셋.
 * <ol>
 *   <li>DB를 쓰는 앱 전부가 같은 부팅 대기를 둔다. 한 앱만 빠지면 그 앱만 동시 기동에서 죽는다.</li>
 *   <li>실행 중 연결 기한은 3초 그대로다. 부팅 대기를 늘리려고 이 값을 바꾸면 장애 때 요청이
 *       더 오래 붙잡히고 환불 재시도 시간 계산이 바뀐다(결정 136 C안을 고르지 않은 이유).</li>
 *   <li>부팅 재시도 예산이 Compose health가 unhealthy로 판정할 수 있는 가장 이른 시각보다 먼저 끝난다.
 *       아니면 정상적으로 기다리는 앱이 unhealthy가 되어 {@code docker compose up --wait}가 실패한다.</li>
 * </ol>
 *
 * <p>재시도 예산은 종료 시각의 상한이 아니다. Hikari는 연결 시도가 돌아온 뒤에 경과 시간을 보므로,
 * DB가 TCP 연결만 받고 MySQL 인사를 보내지 않으면 한 번의 시도가 드라이버 안에서 예산보다 오래 멈출 수 있다.
 * {@code connection-timeout}은 그 시도를 끊지 않는다. 이 설정이 생기기 전에도 같다.
 */
class DatabaseStartupWaitContractTest {

    /** 결정 136의 부팅 재시도 예산({@code initialization-fail-timeout}). */
    private static final Duration STARTUP_WAIT = Duration.ofSeconds(60);

    /** 실행 중 요청의 연결 기한. 결정 136은 이 값을 바꾸지 않는다. */
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(3);

    /** 결정 136을 정할 때 DB를 쓰던 앱. 목록이 비어 조용히 통과하지 않게 센다. */
    private static final List<String> DATABASE_APPS = List.of(
            "auth", "catalog", "license", "order", "payment", "review", "settlement", "store", "studio");

    private static List<Path> databaseApps() {
        return appDirectories().stream()
                .filter(app -> at(applicationYaml(app), "spring.datasource.url") != null)
                .toList();
    }

    private static Map<String, Object> applicationYaml(Path app) {
        return loadYaml(app.resolve("src/main/resources/application.yml"));
    }

    private static Duration millis(Object raw) {
        return raw == null ? null : Duration.ofMillis(Long.parseLong(raw.toString().trim()));
    }

    @Test
    @DisplayName("DB를 쓰는 앱이 실제로 잡혔다 — 0개를 훑고 초록이 되지 않는다")
    void theContractActuallyHasSubjects() {
        assertThat(databaseApps()).extracting(app -> app.getFileName().toString())
                .containsAll(DATABASE_APPS);
    }

    @Test
    @DisplayName("DB를 쓰는 앱 전부가 부팅 때 첫 연결을 60초 동안 다시 시도한다")
    void everyDatabaseAppWaitsForTheFirstConnectionAtBoot() {
        List<String> failFast = new ArrayList<>();
        for (Path app : databaseApps()) {
            Duration wait = millis(at(applicationYaml(app), "spring.datasource.hikari.initialization-fail-timeout"));
            if (!STARTUP_WAIT.equals(wait)) {
                failFast.add("%s (initialization-fail-timeout=%s)".formatted(app.getFileName(), wait));
            }
        }
        assertThat(failFast)
                .as("""
                        부팅 대기가 없으면 Hikari는 첫 연결을 한 번만 시도한다. 여러 JVM이 함께 떠
                        첫 연결이 늦으면 그 앱만 Flyway 단계에서 죽는다(결정 136).
                          고치는 법: application.yml 에 spring.datasource.hikari.initialization-fail-timeout: 60000""")
                .isEmpty();
    }

    @Test
    @DisplayName("실행 중 요청의 연결 기한은 3초 그대로다")
    void runtimeConnectionTimeoutStaysThreeSeconds() {
        List<String> changed = new ArrayList<>();
        for (Path app : databaseApps()) {
            Duration timeout = millis(at(applicationYaml(app), "spring.datasource.hikari.connection-timeout"));
            if (!CONNECTION_TIMEOUT.equals(timeout)) {
                changed.add("%s (connection-timeout=%s)".formatted(app.getFileName(), timeout));
            }
        }
        assertThat(changed)
                .as("부팅 대기는 initialization-fail-timeout 으로만 둔다. connection-timeout 을 늘리면 "
                        + "장애 때 요청이 더 오래 붙잡힌다(결정 136)")
                .isEmpty();
    }

    /**
     * Compose health가 unhealthy로 판정할 수 있는 가장 이른 시각과 비교한다.
     *
     * <p>{@code start_period}가 끝난 직후의 probe부터 실패가 집계되므로, 가장 이른 판정은
     * {@code start_period + interval × (retries - 1)}이다. 비교할 값은 재시도 예산에 마지막 시도 몫으로
     * 연결 기한을 더한 것이다. 연결이 거절되거나 드라이버가 연결 기한 안에 돌아오는 보통의 경우이고,
     * 종료 시각의 상한은 아니다(클래스 설명).
     */
    @Test
    @DisplayName("부팅 재시도 예산이 Compose health의 가장 이른 unhealthy 판정보다 먼저 끝난다")
    void startupWaitEndsBeforeComposeMarksTheAppUnhealthy() {
        Map<String, Object> services = asMap(loadYaml(repoRoot().resolve(APPS_COMPOSE)).get("services"));
        List<String> unhealthyWhileWaiting = new ArrayList<>();
        int checked = 0;
        for (Path app : databaseApps()) {
            String name = app.getFileName().toString();
            Map<String, Object> health = asMap(asMap(services.get(name)).get("healthcheck"));
            assertThat(health).as("%s healthcheck", name).containsKeys("start_period", "interval", "retries");
            long retries = Long.parseLong(health.get("retries").toString());
            Duration earliestUnhealthy = parseDuration(health.get("start_period"))
                    .plus(parseDuration(health.get("interval")).multipliedBy(retries - 1));
            Duration budgetWithLastAttempt = STARTUP_WAIT.plus(CONNECTION_TIMEOUT);
            if (budgetWithLastAttempt.compareTo(earliestUnhealthy) >= 0) {
                unhealthyWhileWaiting.add("%s (예산 %s ≥ 가장 이른 unhealthy %s)"
                        .formatted(name, budgetWithLastAttempt, earliestUnhealthy));
            }
            checked++;
        }
        assertThat(checked).as("Compose에서 확인한 DB 앱 수").isGreaterThanOrEqualTo(DATABASE_APPS.size());
        assertThat(unhealthyWhileWaiting)
                .as("재시도 예산이 health 판정보다 길면 정상적으로 기다리는 앱이 unhealthy가 된다")
                .isEmpty();
    }
}
