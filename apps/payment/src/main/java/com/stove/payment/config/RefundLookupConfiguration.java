package com.stove.payment.config;

import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.domain.RefundLookupInterval;
import com.stove.payment.core.port.PaymentTracing;
import com.stove.payment.core.port.RefundExecutionTraceContexts;
import com.stove.payment.core.service.ReconcileRefundPayment;
import com.stove.payment.core.service.ResolveRefundLookup;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false) @EnableScheduling
@ConditionalOnProperty(name = {"stove.payment.refund-lookup.enabled"}, havingValue = "true")
public class RefundLookupConfiguration {
    @Bean ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("payment-scan-");
        return scheduler;
    }

    @Bean ThreadPoolTaskExecutor refundLookupExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4); executor.setMaxPoolSize(4); executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("refund-lookup-");
        return executor;
    }

    @Bean ThreadPoolTaskScheduler refundLookupTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("refund-lookup-scan-");
        return scheduler;
    }

    @Bean ResolveRefundLookup resolveRefundLookup(PaymentRepository payments, RefundExecutionRepository executions,
            ReconcileRefundPayment reconcile, PlatformTransactionManager transactions, Clock refundReceptionClock,
            @Qualifier("refundLookupExecutor") ThreadPoolTaskExecutor executor, PortOneProperties portone,
            RefundExecutionTraceContexts traceContexts, PaymentTracing tracing, RefundLookupProperties properties) {
        portone.requireConfigured();
        return new ResolveRefundLookup(payments, executions, reconcile, transactions, refundReceptionClock, executor, lookupLease(portone), 20,
                traceContexts, tracing, lateInterval(properties));
    }

    /** Decisions 122·128: the lookup delay past the original window and after a failure has its own keys, capped at 10 minutes. */
    static RefundLookupInterval lateInterval(RefundLookupProperties properties) {
        return new RefundLookupInterval(Duration.ofMillis(properties.lateInitialMs()), Duration.ofMillis(properties.lateMaxMs()));
    }

    /** One PortOne GET plus margin. The operator closure (decision 127) holds the same lease while it re-inspects the PG. */
    static Duration lookupLease(PortOneProperties portone) {
        return portone.connectTimeout().plus(portone.readTimeout()).plusSeconds(30);
    }
}
