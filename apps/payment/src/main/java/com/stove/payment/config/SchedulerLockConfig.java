package com.stove.payment.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 스케줄 작업의 분산 락. <b>락만으로는 부족하다</b> —
 * 락이 없어도 이중 환불은 아니고, 락이 있어도 중단은 생긴다. docs/code-notes.md
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
