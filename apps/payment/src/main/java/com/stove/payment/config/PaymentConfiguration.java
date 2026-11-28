package com.stove.payment.config;

import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.payment.adapter.pg.ImmediateApprovalPgGateway;
import com.stove.payment.adapter.trace.DisabledPaymentTracing;
import com.stove.payment.adapter.trace.JdbcPaymentTraceContexts;
import com.stove.payment.adapter.trace.MicrometerPaymentTracing;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.payment.core.domain.ApprovalClosureRepository;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentApprovalRepository;
import com.stove.payment.core.domain.PaymentOrderLifecycleRepository;
import com.stove.payment.core.service.ApplyOrderExpiry;
import com.stove.payment.core.port.OrderPaymentLifecycleLocks;
import com.stove.payment.adapter.order.JdbcOrderPaymentLifecycleLocks;
import com.stove.payment.core.domain.FailedPaymentAttemptRepository;
import com.stove.payment.core.port.PaymentAttemptLookup;
import com.stove.payment.core.port.PgGateway;
import com.stove.payment.core.port.PaymentTraceContexts;
import com.stove.payment.core.port.PaymentTracing;
import com.stove.payment.core.service.ReadApprovalReviewNotice;
import com.stove.payment.core.service.ResolvePayment;
import com.stove.payment.core.service.ReadPaymentFailureHistory;
import com.stove.payment.core.service.ReceiveOrderPayment;
import jakarta.persistence.EntityManager;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class PaymentConfiguration {
    @Bean OrderPaymentLifecycleLocks orderPaymentLifecycleLocks(JdbcTemplate jdbc) { return new JdbcOrderPaymentLifecycleLocks(jdbc); }
    @Bean ApplyOrderExpiry applyOrderExpiry(PaymentOrderLifecycleRepository lifecycle,PaymentRepository payments,PaymentApprovalRepository approvals,ProcessedEventGuard inbox,OrderPaymentLifecycleLocks locks) {
        return new ApplyOrderExpiry(lifecycle,payments,approvals,inbox,locks);
    }
    @Bean ReadApprovalReviewNotice readApprovalReviewNotice(PaymentApprovalRepository approvals, ApprovalClosureRepository closures) {
        return new ReadApprovalReviewNotice(approvals, closures);
    }
    @Bean ReadPaymentFailureHistory readPaymentFailureHistory(PaymentRepository payments, FailedPaymentAttemptRepository attempts) {
        return new ReadPaymentFailureHistory(payments, attempts);
    }

    @Bean
    ResolvePayment resolvePayment(PaymentRepository payments, PgGateway gateway, OutboxRecorder outbox,
            EntityManager entityManager, PlatformTransactionManager transactions, PaymentProcessingProperties properties,
            PaymentTraceContexts traceContexts, PaymentTracing tracing, PgProperties pg,
            PaymentAttemptLookup attemptLookup, FailedPaymentAttemptRepository failedAttempts, PaymentApprovalRepository approvals,
            Clock refundReceptionClock) {
        return new ResolvePayment(payments, gateway, outbox, entityManager, transactions,
                properties.batchSize(), Duration.ofMillis(properties.retryDelayMs()), traceContexts, tracing, pg.provider(), attemptLookup, failedAttempts,
                approvals, refundReceptionClock);
    }

    @Bean
    @ConditionalOnProperty(name = "stove.payment.pg.provider", havingValue = "SIMULATED", matchIfMissing = true)
    PaymentAttemptLookup simulatedPaymentAttemptLookup() { return purchase -> List.of(); }

    /** 결정 in-process-pg-approval: PG 어댑터가 없는 동안 외부 호출 없이 승인으로 답한다. 실제 PG를 되살릴 때 지운다. */
    @Bean
    @ConditionalOnProperty(name = "stove.payment.pg.provider", havingValue = "SIMULATED", matchIfMissing = true)
    ImmediateApprovalPgGateway immediateApprovalPgGateway(Clock refundReceptionClock) {
        return new ImmediateApprovalPgGateway(refundReceptionClock);
    }

    @Bean
    ReceiveOrderPayment receiveOrderPayment(PaymentRepository payments, ProcessedEventGuard guard, PgProperties properties,
            PaymentTraceContexts traceContexts,PaymentOrderLifecycleRepository lifecycle,OrderPaymentLifecycleLocks locks) {
        return new ReceiveOrderPayment(payments,guard,properties.provider(),traceContexts,lifecycle,locks);
    }

    @Bean
    PaymentTraceContexts paymentTraceContexts(JdbcTemplate jdbc) {
        return new JdbcPaymentTraceContexts(jdbc);
    }

    @Bean
    PaymentTracing paymentTracing(ObjectProvider<Tracer> tracers, ObjectProvider<Propagator> propagators) {
        Tracer tracer = tracers.getIfAvailable();
        Propagator propagator = propagators.getIfAvailable();
        return tracer == null || propagator == null
                ? new DisabledPaymentTracing() : new MicrometerPaymentTracing(tracer, propagator);
    }
}
