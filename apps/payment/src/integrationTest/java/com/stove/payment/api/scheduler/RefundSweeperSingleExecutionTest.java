package com.stove.payment.api.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.stove.common.testcontainers.InfraContainers;
import com.stove.payment.api.application.RefundFacade;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 시나리오 R-08 — <b>인스턴스가 여러 대여도 스윕이 한 번만 도는가.</b>
 *
 * <h2>지금까지 무엇이 검증됐나</h2>
 *
 * <p>{@code SettlementBatchLockTest} 가 이 저장소의 유일한 락 테스트인데, 판정 셋이 전부 <b>구조적</b>이다.
 *
 * <ul>
 *   <li>"분산 락 공급자가 구성돼 있다" — 빈이 있는가</li>
 *   <li>"락 저장소 테이블이 마이그레이션으로 만들어져 있다" — 테이블이 있는가</li>
 *   <li>"마감 배치에 단일 실행 잠금이 <b>선언</b>돼 있다" — 애노테이션이 붙어 있는가</li>
 * </ul>
 *
 * <p><b>셋 다 "장치가 있다" 까지고 "장치가 그 자리를 지킨다" 는 없다.</b>
 * 이 저장소가 D-021·D-023·D-031·D-035 로 네 번 밟은 부류이고,
 * 애노테이션이 붙어 있어도 {@code @EnableSchedulerLock} 이 빠지거나 프록시가 우회되면
 * <b>락은 조용히 아무 일도 하지 않는다.</b>
 *
 * <h2>락이 막는 것은 이중 환불이 아니다</h2>
 *
 * <p>{@code V7__shedlock.sql} 과 {@link RefundSweeper} 가 같은 말을 적어 두었다 —
 * 이중 환불은 {@code PgClient#cancel} 의 {@code pgTxId} 멱등 계약이 막는다.
 * 락이 막는 것은 <b>"몇 번 시도했는지 알 수 없게 되는 것"</b> 이고,
 * 그 값이 곧 PG 연동이 정상인지 보는 창이다. 창이 닫히는 것은 조용한 실패다.
 *
 * <h2>기동 직후의 락을 먼저 치운다</h2>
 *
 * <p>{@code @Scheduled} 는 주기와 무관하게 <b>기동 직후 한 번</b> 발화하고,
 * {@code lockAtLeastFor = "PT30S"} 라 그 회차가 락을 30초 쥔다. 그대로 재면 두 스레드가
 * <b>둘 다 건너뛰어</b> 실행 0회가 되고, 그건 "락이 동작했다" 가 아니라 <b>"아무것도 재지 않았다"</b> 다.
 * 그래서 {@link #releaseStartupLock()} 이 먼저 온다 — 주입 확인과 같은 자리다.
 */
@SpringBootTest(properties = {
        "stove.outbox.relay-enabled=false",
        // 배경 스케줄러가 판정 도중에 끼어들지 않게 한 시간으로 미룬다.
        // 기동 직후 1회는 그래도 발화하므로 아래에서 그 락을 치운다.
        "stove.payment.refund-sweep-interval-ms=3600000",
        // 락을 얻었는지 건너뛰었는지는 DEBUG 에만 남는다. 이 판정이 빨개졌을 때
        // "동시성 문제" 와 "배선이 아예 안 됐다" 를 로그 없이 가를 수 없다.
        "logging.level.net.javacrumbs.shedlock=DEBUG"
})
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class RefundSweeperSingleExecutionTest {

    private static final String LOCK_NAME = "payment-resume-stranded-refunds";

    @Autowired
    RefundSweeper refundSweeper;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    RefundFacade refundFacade;

    /**
     * 기동 직후 회차가 남긴 것을 둘 다 치운다 — <b>락과 호출 기록</b>.
     *
     * <p>스파이를 리셋하지 않으면 그 1회가 세어져 {@code times(1)} 이 2가 된다.
     * 컨텍스트가 캐시되므로 두 번째 판정에는 첫 판정의 호출까지 얹힌다.
     *
     * <h2>행을 지우지 않는다 — 지우면 락이 꺼진다</h2>
     *
     * <p>처음에 {@code delete from shedlock} 으로 풀었더니 <b>그 뒤의 모든 획득이 실패했다.</b>
     * 대조군이 "0회 실행 · 락 행 없음" 으로 빨개져서 원인을 찾았다.
     *
     * <p>ShedLock 의 저장소 기반 공급자는 <b>"이 이름의 행이 있다" 를 메모리에 캐시한다.</b>
     * 있다고 믿는 동안은 INSERT 대신 {@code UPDATE ... WHERE lock_until <= now()} 로 획득하는데,
     * 행이 사라졌으면 그 UPDATE 가 0건이라 <b>"이미 잠겨 있다" 와 구분되지 않는다.</b>
     * 결과는 조용한 스킵이다 — 락을 끈 것과 같은 상태가 되고, 아무 예외도 나지 않는다.
     *
     * <p>그래서 지우는 대신 <b>만료시킨다.</b> 행은 그대로 두고 {@code lock_until} 을 과거로 민다.
     * 행이 아예 없는 첫 회차에는 0건이 갱신되고, 그때는 공급자가 INSERT 경로로 정상 획득한다.
     */
    @BeforeEach
    void resetStartupState() {
        awaitStartupSweepFinished();
        jdbcTemplate.update("update shedlock set lock_until = locked_at where name = ?", LOCK_NAME);
        org.mockito.Mockito.reset(refundFacade);
    }

    private Map<String, Object> lockRow() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select name, timestampdiff(second, locked_at, lock_until) as held_seconds "
                        + "from shedlock where name = ?", LOCK_NAME);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /** 락 행이 없으면 -1. "없다" 와 "0초 보유" 를 같은 값으로 만들지 않는다. */
    private long heldSeconds() {
        Object held = lockRow().get("held_seconds");
        return held == null ? -1L : ((Number) held).longValue();
    }

    /**
     * 기동 직후 회차가 <b>끝날 때까지 기다린 뒤</b> 락을 만료시킨다.
     *
     * <p>기다리지 않으면 그 회차와 경쟁한다. 실제로 그렇게 빨개졌다 —
     * 스케줄러가 {@code 21.614} 에 락을 잡고, 40ms 뒤 두 스레드가 {@code 21.647}·{@code 21.657} 에
     * 도착해 <b>둘 다 {@code Not executing} 으로 건너뛰었다.</b>
     * 실행 0회를 "락이 잘 막았다" 로 읽으면 안 되는 이유가 이것이다.
     *
     * <p>끝났다는 신호는 <b>보유 시간</b>이 알려 준다. 획득 시점의 {@code lock_until} 은
     * {@code now + lockAtMostFor}(5분)이고, 정상 종료 시 {@code locked_at + lockAtLeastFor}(30초)로
     * 줄어든다 — 그러니 보유 시간이 300초대에서 30초대로 떨어지는 순간이 종료 시점이다.
     */
    private void awaitStartupSweepFinished() {
        Awaitility.await("기동 직후 스윕 종료")
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(200))
                .pollDelay(Duration.ZERO)
                .until(() -> {
                    long held = heldSeconds();
                    return held >= 0 && held <= 60;
                });
    }

    /**
     * <b>대조군.</b> 동시성을 재기 전에 "한 번 부르면 한 번 돈다" 부터 성립해야 한다.
     *
     * <p>이게 빨개지면 문제는 락이 아니라 <b>배선</b>이다 — 프록시가 안 걸렸거나
     * 스파이가 스윕에 주입되지 않았거나 락 획득이 조용히 실패한 것이고, 그 셋은 대응이 다르다.
     * 대조군 없이 동시성 판정만 두면 "0회 실행" 을 보고 락이 잘 막았다고 읽을 수도 있다.
     */
    @Test
    @DisplayName("대조군 — 한 번 발화하면 스윕 본문이 한 번 돈다")
    void aSingleFiringRunsTheBodyOnce() {
        refundSweeper.resumeStrandedRefunds();

        verify(refundFacade, times(1)).resumeStranded();
        assertThat(lockRow())
                .as("본문은 돌았는데 락 행이 없으면 ShedLock 프록시가 이 호출을 가로채지 않은 것이다")
                .isNotEmpty();
    }

    @Test
    @DisplayName("두 인스턴스가 동시에 발화해도 스윕 본문은 한 번만 돈다")
    void concurrentFiringRunsTheSweepOnce() throws Exception {
        int instances = 2;
        CyclicBarrier sameMoment = new CyclicBarrier(instances);
        ExecutorService pool = Executors.newFixedThreadPool(instances);

        // 배리어가 있어야 '동시' 다. 순차로 부르면 두 번째가 lockAtLeastFor 때문에 건너뛰는데,
        // 그건 동시 실행 창을 닫았다는 증거가 아니라 락이 아직 살아 있다는 증거일 뿐이다.
        Callable<Void> fire = () -> {
            sameMoment.await(20, TimeUnit.SECONDS);
            refundSweeper.resumeStrandedRefunds();
            return null;
        };

        try {
            List<Future<Void>> fired = List.of(pool.submit(fire), pool.submit(fire));
            for (Future<Void> f : fired) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        verify(refundFacade, times(1)).resumeStranded();

        assertThat(lockRow())
                .as("""
                        락 행이 없으면 ShedLock 이 아예 개입하지 않은 것이다.
                        그 상태에서 실행이 1회였다면 락이 막은 것이 아니라 우연이다 —
                        @EnableSchedulerLock · LockProvider 빈 · V7 마이그레이션 순으로 본다.""")
                .isNotEmpty()
                .containsEntry("name", LOCK_NAME);
    }

    /**
     * 락은 <b>보유 시간</b>도 계약이다.
     *
     * <p>{@code lockAtLeastFor} 가 무시되면 시계가 조금 어긋난 두 번째 인스턴스가
     * 곧바로 다시 돌 수 있다. #42 가 원격에서 손으로 확인한 값(30초)을 여기서 회귀로 고정한다.
     */
    @Test
    @DisplayName("한 회차가 끝나도 lockAtLeastFor 만큼은 락을 쥔다 — 뒤늦은 발화를 막는다")
    void lockIsHeldForAtLeastTheDeclaredWindow() {
        refundSweeper.resumeStrandedRefunds();

        assertThat(lockRow())
                .as("스윕이 돌았는데 락 행이 없다 — 락이 개입하지 않았다는 뜻이다")
                .isNotEmpty();
        // TIMESTAMPDIFF 는 초 단위로 내림하므로 29.9초가 29 로 읽힌다. 값을 못 박지 않고
        // "선언한 창 근처인가" 를 묻는다 — 30 을 40 으로 바꾸면 여기가 빨개지는 것이 목적이다.
        assertThat(((Number) lockRow().get("held_seconds")).longValue())
                .as("@SchedulerLock(lockAtLeastFor = \"PT30S\") 가 그대로 반영돼야 한다")
                .isBetween(29L, 31L);
    }
}
