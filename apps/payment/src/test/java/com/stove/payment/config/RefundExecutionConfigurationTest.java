package com.stove.payment.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.stove.payment.core.service.RequestFirstRefundCancellation;
import com.stove.payment.core.service.RequestRefundCancellation;
import com.stove.payment.core.service.ResolveRefundLookup;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RefundExecutionConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(RefundExecutionConfiguration.class)
            .withBean(RequestRefundCancellation.class, () -> mock(RequestRefundCancellation.class));

    @Test void readOnlyLookupDoesNotEnableCancellationPosts() {
        context.withBean(ResolveRefundLookup.class, () -> mock(ResolveRefundLookup.class))
                .run(application -> assertThat(application).doesNotHaveBean(RequestFirstRefundCancellation.class));
    }

    @Test void enabledExecutionRequiresTheDurableLookupContinuation() {
        context.withPropertyValues("stove.payment.refund-execution.enabled=true")
                .run(application -> assertThat(application).hasFailed());
    }

    @Test void executionAndLookupTogetherEnableTheSynchronousKafkaContinuation() {
        context.withBean(ResolveRefundLookup.class, () -> mock(ResolveRefundLookup.class))
                .withPropertyValues("stove.payment.refund-execution.enabled=true")
                .run(application -> assertThat(application).hasSingleBean(RequestFirstRefundCancellation.class));
    }
}
