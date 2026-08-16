package com.stove.order.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 만료 스윕의 분산 락.
 *
 * <p><b>여기서 락이 막는 것은 사고가 아니라 상한이다.</b> payment 의 환불 재개는 되돌릴 수 없는
 * 외부 호출(PG)을 동반해서 동시 실행 자체가 문제였지만, 만료는 로컬 상태 변경뿐이라
 * 같은 행을 두 인스턴스가 집으면 하나가 {@code CONFLICT} 로 튕기고 끝난다.
 *
 * <p>그런데도 잠그는 이유는 <b>배치 크기가 뜻을 잃기 때문</b>이다. 대수만큼 동시에 돌면 한 회차에
 * 만료되는 수가 {@code expire-batch-size} 가 아니라 그 값 × 인스턴스 수가 된다.
 * 밀린 것을 나눠 처리하려고 둔 상한인데 그게 인스턴스 수에 따라 달라지면 상한이 아니다.
 *
 * <p>{@code defaultLockAtMostFor} 를 폴링 주기보다 넉넉히 잡는다 — 락을 쥔 인스턴스가 죽어도
 * 이 시간이 지나면 풀린다. settlement(월 마감, 30분)보다 짧은 이유는 여기가 분 단위로 도는
 * 스윕이라서다.
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
