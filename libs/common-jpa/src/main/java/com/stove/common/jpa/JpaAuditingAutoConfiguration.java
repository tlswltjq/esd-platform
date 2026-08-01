package com.stove.common.jpa;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@AutoConfiguration
@ConditionalOnClass(EnableJpaAuditing.class)
@EnableJpaAuditing
public class JpaAuditingAutoConfiguration {
}
