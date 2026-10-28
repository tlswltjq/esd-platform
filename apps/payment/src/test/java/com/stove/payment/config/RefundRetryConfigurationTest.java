package com.stove.payment.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.stove.payment.api.scheduler.RefundRetryScheduler;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.service.RequestFirstRefundCancellation;
import com.stove.payment.core.service.RequestRefundCancellation;
import com.stove.payment.core.service.ResolveRefundRetry;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class RefundRetryConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(RefundRetryConfiguration.class, RefundRetryScheduler.class)
            .withBean(RefundExecutionRepository.class, () -> mock(RefundExecutionRepository.class))
            .withBean(RequestRefundCancellation.class, () -> mock(RequestRefundCancellation.class))
            .withBean(Clock.class, Clock::systemUTC);

    @Test void readOnlyLookupOrFirstDispatchDoesNotImplicitlyEnableAutomaticRetries() {
        context.withPropertyValues("stove.payment.refund-lookup.enabled=true", "stove.payment.refund-execution.enabled=true")
                .run(application -> assertThat(application).doesNotHaveBean(ResolveRefundRetry.class)
                        .doesNotHaveBean(RefundRetryScheduler.class));
    }

    @Test void enabledRetriesRequireTheConfiguredFirstDispatchAndItsLookupDependency() {
        context.withPropertyValues("stove.payment.refund-retry.enabled=true")
                .run(application -> assertThat(application).hasFailed());
    }

    @Test void enabledRetryWorkHasOneBoundedWorkerAndItsOwnScanner() {
        context.withBean(RequestFirstRefundCancellation.class, () -> mock(RequestFirstRefundCancellation.class))
                .withPropertyValues("stove.payment.refund-retry.enabled=true").run(application -> {
                    assertThat(application).hasSingleBean(ResolveRefundRetry.class).hasSingleBean(RefundRetryScheduler.class);
                    ThreadPoolTaskExecutor executor = application.getBean("refundRetryExecutor", ThreadPoolTaskExecutor.class);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(1);
                    assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isZero();
                    assertThat(application).hasBean("refundRetryTaskScheduler");
                });
    }
}
