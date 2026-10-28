package com.stove.payment.config;

import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.service.RequestFirstRefundCancellation;
import com.stove.payment.core.service.RequestRefundCancellation;
import com.stove.payment.core.service.ResolveRefundRetry;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false) @EnableScheduling
@ConditionalOnProperty(name = "stove.payment.refund-retry.enabled", havingValue = "true")
public class RefundRetryConfiguration {
    @Bean ThreadPoolTaskExecutor refundRetryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("refund-retry-"); return executor;
    }

    @Bean ThreadPoolTaskScheduler refundRetryTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("refund-retry-scan-"); return scheduler;
    }

    @Bean ResolveRefundRetry resolveRefundRetry(RefundExecutionRepository executions, RequestRefundCancellation cancellations,
            Clock refundReceptionClock, @Qualifier("refundRetryExecutor") ThreadPoolTaskExecutor executor,
            RequestFirstRefundCancellation first) {
        // First dispatch requires durable lookup; retry activation must preserve the same configured continuation.
        return new ResolveRefundRetry(executions, cancellations, refundReceptionClock, executor, 20);
    }
}
