package com.stove.archfixture.precision.config;

import com.stove.archfixture.precision.api.scheduler.WorkScheduler;
import com.stove.archfixture.precision.core.service.ReadRows;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ScheduleConfiguration {
    @Bean public WorkScheduler workScheduler(ReadRows read) { return new WorkScheduler(read); }
}
