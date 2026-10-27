package com.stove.payment.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.stove.payment.api.scheduler.RefundLookupScheduler;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.service.ReconcileRefundPayment;
import com.stove.payment.core.service.ResolveRefundLookup;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

class RefundLookupConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(RefundLookupConfiguration.class, RefundLookupScheduler.class)
            .withBean(PaymentRepository.class, () -> mock(PaymentRepository.class))
            .withBean(RefundExecutionRepository.class, () -> mock(RefundExecutionRepository.class))
            .withBean(ReconcileRefundPayment.class, () -> mock(ReconcileRefundPayment.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(PortOneProperties.class, () -> new PortOneProperties(URI.create("http://127.0.0.1"), "fixture",
                    "store-fixture", "channel-fixture", "fixture", Duration.ofSeconds(2), Duration.ofSeconds(65),
                    URI.create("https://fixture.example/webhook")));

    @Test void enabledLookupUsesABoundedExecutorAndADifferentSchedulerFromPurchaseProcessing() {
        context.withPropertyValues("stove.payment.refund-lookup.enabled=true").run(application -> {
            assertThat(application).hasSingleBean(ResolveRefundLookup.class).hasSingleBean(RefundLookupScheduler.class);
            assertThat(application.getBean("refundLookupTaskScheduler", ThreadPoolTaskScheduler.class))
                    .isNotSameAs(application.getBean("taskScheduler", ThreadPoolTaskScheduler.class));
            ThreadPoolTaskExecutor executor = application.getBean(ThreadPoolTaskExecutor.class);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isZero();
        });
    }

    @Test void lookupSchedulingRequiresExplicitActivation() {
        context.run(application -> assertThat(application).doesNotHaveBean(ResolveRefundLookup.class)
                .doesNotHaveBean(RefundLookupScheduler.class).doesNotHaveBean(ThreadPoolTaskExecutor.class));
    }
}
