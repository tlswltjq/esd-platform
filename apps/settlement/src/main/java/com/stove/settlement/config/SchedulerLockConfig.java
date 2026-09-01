package com.stove.settlement.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 스케줄 배치의 분산 락. <b>이것만으로는 부족하다</b> —
 * 트랜잭션 안의 비보상 외부 호출은 단일 인스턴스에서도 사고가 된다. docs/code-notes.md
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
                        // DB 시계로 잡는다 — 인스턴스 시계 동기화를 가정하지 않는다.
                        .usingDbTime()
                        .build());
    }
}
