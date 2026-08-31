package com.stove.order.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 만료 스윕의 분산 락. <b>여기서 락이 막는 것은 사고가 아니라 상한이다</b> —
 * 대수만큼 동시에 돌면 {@code expire-batch-size} 가 뜻을 잃는다. docs/code-notes.md
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
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
