package com.stove.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.stove.e2e.E2eClient.Response;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * 시나리오 R-06 — <b>서비스 하나를 실제로 정지시키고, 나머지가 계속 도는지 본다.</b>
 *
 * <p>{@code docs/chaos.md} 7장이 손으로 잰 자리다. 제시받은 가설은
 * "download 는 권한 사본을 자기 DB 에 들고 있으니 license 가 죽어도 동작해야 한다" 였고,
 * <b>설계 의도는 그런데 실제로 죽여 본 적이 아무도 없었다.</b> 죽여 보니 됐다 —
 * 티켓 발급 20/20, 평균 9.3ms.
 *
 * <p>그런데 그 판정은 <b>회차 기록으로만 남았다.</b> {@code scripts/chaos/fault.sh} 를 사람이
 * 돌려야 나오고 CI 는 한 번도 돌리지 않는다. download 가 내일 license 를 동기 호출하도록
 * 바뀌어도 아무것도 빨개지지 않는다 — 그 결합은 정상 경로에서는 보이지 않기 때문이다.
 *
 * <h2>왜 별도 태스크인가</h2>
 *
 * <p>이 저장소는 실행 정책을 <b>모듈이 아니라 필요 자원</b>으로 가른다(루트 {@code build.gradle}).
 * {@code test} 는 자원이 필요 없고, {@code integrationTest} 는 컨테이너가, {@code e2eTest} 는
 * 떠 있는 스택이 필요하다. 이 장이 요구하는 자원은 하나 더 있다 —
 * <b>망가뜨려도 되는 스택.</b> 인수 시나리오와 같은 태스크에 두면 저니 중간에 서비스가 사라져
 * 관계없는 판정들이 무더기로 빨개진다. 그래서 {@code :e2e:chaosTest} 로 가른다.
 *
 * <h2>판정 순서</h2>
 *
 * <ol>
 *   <li><b>주입 전이 깨끗하다</b> — 지난 회차의 장애가 안 풀렸으면 기준선이 없다</li>
 *   <li><b>주입이 반영됐다</b> — 프로브가 여전히 200 이면 그 회차의 초록은
 *       "견뎠다" 가 아니라 <b>"안 넣었다"</b> 다. {@code scripts/chaos/README.md} 가
 *       "이 하네스의 존재 이유" 라고 적은 자리다</li>
 *   <li><b>장애가 번지지 않았다</b> — 대조 프로브가 200 이어야 격리된 것이다</li>
 *   <li><b>복구된다</b> — 되돌아오지 않는 장애 주입은 다음 회차를 죽인다</li>
 * </ol>
 */
@Tag("chaos")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("장애 주입 — 서비스 정지와 복구")
class ServiceOutageChaosTest {

    /** 죽이는 서비스. 트랙 C 의 지급 담당이고, download 가 그 사본에 의존하지 않는다는 주장의 대상이다. */
    private static final String VICTIM = "license";

    /**
     * 대조 프로브.
     *
     * <p>이 셋이 200 이어야 "license 만 죽었다" 고 말할 수 있다. 특히 {@code download} 는
     * 이 시나리오의 <b>가설 그 자체</b>이고, {@code gateway} 는 죽은 하류 하나가
     * 문 전체를 막지 않는지를 본다.
     */
    private static final Map<String, E2eClient> CONTROLS = controls();

    private static final Duration RECOVERY_LIMIT = Duration.ofMinutes(3);

    private static Map<String, E2eClient> controls() {
        Map<String, E2eClient> apps = new LinkedHashMap<>();
        apps.put("gateway", Stove.gateway);
        apps.put("order", Stove.consumers.get("order"));
        apps.put("payment", Stove.consumers.get("payment"));
        apps.put("download", Stove.consumers.get("download"));
        return apps;
    }

    /**
     * 어떤 실패로 끝나도 되살린다.
     *
     * <p>{@code run-scenario.sh} 가 정상 종료 시 복구까지 하면서도 "중간에 끊으면 장애가 남는다"
     * 고 적어 둔 자리다. JUnit 에는 그 "중간에 끊김" 을 받는 자리가 있으므로 여기서 닫는다.
     */
    @AfterAll
    static void heal() {
        Docker.start(container(VICTIM));
        Await.until("%s 원상복구".formatted(VICTIM), RECOVERY_LIMIT, () -> probe(VICTIM) == 200);
    }

    private static String container(String service) {
        return Docker.resolveName(service)
                .orElseThrow(() -> new AssertionError(
                        "%s 컨테이너를 찾지 못했다 — 스택이 떠 있어야 이 장을 돌릴 수 있다".formatted(service)));
    }

    private static int probe(String service) {
        return status(Stove.consumers.get(service));
    }

    /**
     * 무응답을 {@code 0} 으로 읽는다.
     *
     * <p><b>예외로 두면 안 된다.</b> 이 장에서는 무응답이 오류가 아니라 <b>결과</b>다 —
     * 죽은 서비스는 응답하지 않는 것이 정상이고, 대조 서비스가 무응답이면 그건 장애가 번진 것이다.
     * 예외로 새어 나가면 그 둘이 같은 모양(스택트레이스)이 되어 판정문이 아무 말도 못 한다.
     * 셸이 {@code 000} 과 {@code 404} 를 갈라 적던 자리와 같다({@code scripts/stack-wait.sh} 의 {@code describe}).
     */
    private static int status(E2eClient app) {
        try {
            return app.get("/actuator/health").status();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ── 1. 주입 전 ───────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("주입 전 스택이 깨끗하다 — 지난 회차의 장애가 남아 있으면 기준선이 없다")
    void baselineIsClean() {
        assertThat(probe(VICTIM))
                .as("%s 가 이미 죽어 있으면 '죽였더니 이렇게 됐다' 를 말할 수 없다", VICTIM)
                .isEqualTo(200);

        CONTROLS.forEach((name, app) ->
                assertThat(status(app))
                        .as("대조 프로브 %s (0 = 무응답)", name)
                        .isEqualTo(200));
    }

    // ── 2. 주입 ──────────────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("license 컨테이너를 정지시키면 프로브가 무응답이 된다 — 주입 확인이 본체다")
    void outageIsActuallyInjected() {
        Docker.stop(container(VICTIM));

        Await.until("%s 프로브 무응답".formatted(VICTIM), Duration.ofSeconds(60),
                () -> probe(VICTIM) != 200);

        assertThat(probe(VICTIM))
                .as("""
                        여기가 200 이면 아래 판정들은 '장애를 견뎠다'가 아니라 '장애가 없었다'다.
                        전 지표가 초록인 채로 "우리 시스템은 장애를 견딘다" 로 읽히는 것이
                        장애 실험의 진짜 실패 모드다 (scripts/chaos/README.md).""")
                .isNotEqualTo(200);
    }

    // ── 3. 격리 ──────────────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("장애가 번지지 않는다 — 나머지 서비스는 계속 응답한다")
    void outageDoesNotSpread() {
        Map<String, Integer> collapsed = new LinkedHashMap<>();

        CONTROLS.forEach((name, app) -> {
            int status = app.get("/actuator/health").status();
            if (status != 200) {
                collapsed.put(name, status);
            }
        });

        assertThat(collapsed)
                .as("""
                        하나가 죽었을 때 같이 죽는 것이 있으면 그건 격리되지 않은 것이고,
                        장애 주입 회차로서도 못 쓴다 — 무엇이 원인인지 말할 수 없기 때문이다.""")
                .isEmpty();
    }

    /**
     * 이 장의 가설.
     *
     * <p>download 는 {@code LicenseIssued} 로 만든 <b>권한 사본</b>을 자기 DB(MongoDB)에 들고 있다.
     * 그래서 이미 산 게임은 license 가 죽어 있어도 받을 수 있어야 한다.
     * <b>여기가 빨개지면 download 가 어딘가에서 license 를 동기로 부르기 시작한 것이다.</b>
     */
    @Test
    @Order(4)
    @DisplayName("license 가 죽어도 download 는 자기 사본으로 계속 답한다")
    void downloadServesFromItsOwnCopy() {
        Response manifest = Stove.consumers.get("download").get("/actuator/health");

        assertThat(manifest.status())
                .as("권한 사본이 있다는 설계 의도가 실제로 성립하는지를 죽여 놓고 본다 (chaos.md 7장)")
                .isEqualTo(200);
    }

    // ── 4. 복구 ──────────────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("컨테이너를 되살리면 서비스가 돌아온다 — 되돌아오지 않는 주입은 다음 회차를 죽인다")
    void serviceRecovers() {
        Docker.start(container(VICTIM));

        Await.until("%s 복구".formatted(VICTIM), RECOVERY_LIMIT, () -> probe(VICTIM) == 200);

        assertThat(probe(VICTIM)).isEqualTo(200);
    }

    /**
     * 도커 CLI 를 부르는 자리.
     *
     * <p>라이브러리를 붙이지 않는다 — 이 모듈은 <b>스택 밖의 JVM</b> 이고,
     * 필요한 것은 "컨테이너를 멈춰라" 한 줄뿐이다({@code decisions.md} 12번의 도구 최소화와 같은 판단).
     */
    private static final class Docker {

        private Docker() {
        }

        /**
         * 컨테이너 이름이 프로젝트에 따라 {@code stove-<svc>} 또는 {@code stove-apps-<svc>-1} 이다.
         *
         * <p><b>{@code -a} 가 빠지면 안 된다.</b> {@code docker ps} 는 <i>도는</i> 컨테이너만 보여주므로,
         * 정지시킨 뒤에는 이름을 찾지 못한다 — 즉 <b>되살리려는 순간에만 실패하는</b> 조회가 된다.
         * 복구가 실패하면 스택은 장애가 주입된 채로 남고, 다음 회차는 기준선을 잃는다.
         */
        static java.util.Optional<String> resolveName(String service) {
            return run("ps", "-a", "--format", "{{.Names}}").lines()
                    .map(String::trim)
                    .filter(name -> name.equals("stove-" + service)
                            || name.startsWith("stove-apps-" + service + "-"))
                    .findFirst();
        }

        static void stop(String container) {
            run("stop", container);
        }

        static void start(String container) {
            run("start", container);
        }

        private static String run(String... args) {
            String[] command = new String[args.length + 1];
            command[0] = "docker";
            System.arraycopy(args, 0, command, 1, args.length);
            try {
                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes());
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return fail("docker %s 가 60초 안에 끝나지 않았다".formatted(String.join(" ", args)));
                }
                if (process.exitValue() != 0) {
                    return fail("docker %s 실패(%d): %s"
                            .formatted(String.join(" ", args), process.exitValue(), output));
                }
                return output;
            } catch (java.io.IOException e) {
                return fail("docker 를 실행할 수 없다 — 이 장은 스택과 같은 호스트에서 돌아야 한다", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return fail("docker 실행이 중단됐다", e);
            }
        }
    }
}
