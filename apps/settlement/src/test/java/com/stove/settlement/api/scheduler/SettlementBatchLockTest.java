package com.stove.settlement.api.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.test.InfraContainers;
import java.time.Duration;
import java.time.temporal.TemporalAmount;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 월 마감 배치의 <b>단일 실행 보장</b>이 실제로 걸려 있는가.
 *
 * <p>{@code @SchedulerLock} 은 애노테이션 하나라 지워도 컴파일이 통과하고 테스트도 통과한다 —
 * 그런데 지워지면 인스턴스 대수만큼 마감이 동시에 돈다. 그 선언을 값으로 고정한다.
 *
 * <p>여기서 확인하지 <b>않는</b> 것도 분명히 해 둔다. 실제 경합(두 인스턴스가 동시에
 * 발화했을 때 하나만 통과하는가)은 프로세스 두 개가 필요해 이 층에서 재현할 수 없다.
 * 그건 ShedLock 라이브러리의 책임이고, 여기서는 <b>우리가 그것을 쓰기로 한 결정</b>을 지킨다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class SettlementBatchLockTest {

    @Autowired
    LockProvider lockProvider;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("분산 락 공급자가 구성돼 있다")
    void lockProviderIsConfigured() {
        assertThat(lockProvider).isNotNull();
    }

    @Test
    @DisplayName("락 저장소 테이블이 마이그레이션으로 만들어져 있다")
    void shedlockTableExists() {
        // 테이블이 없으면 락이 런타임에 조용히 실패한다 — 그때는 이미 마감이 두 번 돈 뒤다.
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema = database() and table_name = 'shedlock'",
                Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("마감 배치에 단일 실행 잠금이 선언돼 있다")
    void closeBatchDeclaresSchedulerLock() throws Exception {
        SchedulerLock lock = SettlementBatch.class
                .getMethod("closePreviousMonth")
                .getAnnotation(SchedulerLock.class);

        assertThat(lock).as("@SchedulerLock 이 사라지면 인스턴스 대수만큼 마감이 돈다").isNotNull();
        assertThat(lock.name()).isEqualTo("settlement-close-month");

        // lockAtMostFor: 락을 쥔 인스턴스가 죽어도 이 시간이 지나면 풀린다.
        // 마감 실행 시간보다 넉넉해야 하고, 다음 발화(한 달)보다는 짧아야 한다.
        TemporalAmount atMostFor = Duration.parse(lock.lockAtMostFor());
        assertThat(atMostFor).isEqualTo(Duration.ofHours(2));

        // lockAtLeastFor: 실행이 순식간에 끝나도 이 동안은 락을 쥔다.
        // 인스턴스 간 시계 오차로 두 번째가 뒤늦게 발화해 다시 도는 것을 막는다.
        assertThat(Duration.parse(lock.lockAtLeastFor())).isEqualTo(Duration.ofMinutes(5));
    }
}
