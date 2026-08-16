package com.stove.payment.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 스케줄 작업의 분산 락.
 *
 * <p>인스턴스가 여러 대면 {@code @Scheduled} 는 대수만큼 동시에 발화한다. 중단된 취소 재개는
 * 되돌릴 수 없는 외부 호출(PG 환불)을 동반하므로 실행 자체를 한 번으로 묶는다.
 *
 * <p>잠금 저장소는 MySQL 이다. 이 서비스가 이미 쓰고 있는 것이라 새 인프라가 늘지 않는다.
 * settlement 의 같은 이름 설정과 판단이 같다 — 다만 잠글 대상이 월 마감이 아니라
 * 분 단위로 도는 스윕이라 기본 보유 시간을 짧게 잡는다.
 *
 * <p><b>락만으로는 부족하다.</b> 락은 동시 실행 창을 닫을 뿐이고, 단일 인스턴스에서도
 * PG 호출과 확정 커밋 사이에서 멈추면 {@code CANCELING} 이 남는다. 그쪽은
 * {@code RefundFacade} 의 단계 분리와 PG 멱등 계약이 맡는다 —
 * <b>락이 없어도 이중 환불은 아니고, 락이 있어도 중단은 생긴다.</b> 둘은 다른 문제다.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
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
