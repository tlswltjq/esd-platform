package com.stove.settlement.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 스케줄 배치의 분산 락.
 *
 * <p>인스턴스가 여러 대면 {@code @Scheduled} 는 대수만큼 동시에 발화한다. 마감은 금전 확정이고
 * 되돌릴 수 없는 외부 호출(세금계산서 발행)을 동반하므로 실행 자체를 한 번으로 묶어야 한다.
 *
 * <p>잠금 저장소는 MySQL 이다. 이 서비스가 이미 쓰고 있는 것이라 새 인프라가 늘지 않는다.
 *
 * <p><b>이것만으로는 부족하다는 점을 분명히 해 둔다.</b> 락은 동시 실행 창을 닫을 뿐이고,
 * 트랜잭션 안의 비보상 외부 호출은 단일 인스턴스에서도 사고가 된다. 그쪽은
 * {@code SettlementCloseFacade} 가 단계를 쪼개서 막는다 — 순서가 그 반대였으면
 * 락을 걸어 두고도 같은 사고가 났을 것이다.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
                        // 락 시각을 DB 시계로 잡는다. 인스턴스 시계가 어긋나면 락이
                        // 일찍 풀리거나 늦게 풀린다 — 분산 락에서 시계 동기화를 가정하지 않는다.
                        .usingDbTime()
                        .build());
    }
}
