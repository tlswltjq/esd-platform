package com.stove.order.config;

import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderLicenseGrantRepository;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundAttemptRepository;
import com.stove.order.core.service.RequestRefund;
import com.stove.order.core.service.ReadRefund;
import com.stove.order.core.service.ReadRefundReviews;
import com.stove.order.core.service.DecideRefund;
import com.stove.order.core.service.RequestRefundRetry;
import com.stove.order.core.service.ApplyRefundRestoration;
import com.stove.order.core.service.ApplyLicenseGrant;
import com.stove.order.core.service.ApplyRefundSuspension;
import com.stove.order.core.service.ApplyRefundPaymentConfirmation;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RefundConfiguration {
    @Bean ReadRefundReviews readRefundReviews(OrderRepository orders, RefundRepository refunds) {
        return new ReadRefundReviews(orders, refunds);
    }

    @Bean DecideRefund decideRefund(OrderRepository orders, RefundRepository refunds, OrderLicenseGrantRepository grants,
            OutboxRecorder outbox, Clock refundClock) {
        return new DecideRefund(orders, refunds, grants, outbox, refundClock);
    }

    @Bean ReadRefund readRefund(OrderRepository orders, RefundRepository refunds) {
        return new ReadRefund(orders, refunds);
    }

    @Bean RequestRefundRetry requestRefundRetry(OrderRepository orders, RefundRepository refunds, RefundAttemptRepository attempts,
            OutboxRecorder outbox, EntityManager entityManager, Clock refundClock) {
        return new RequestRefundRetry(orders, refunds, attempts, outbox, entityManager, refundClock);
    }

    @Bean ApplyRefundRestoration applyRefundRestoration(OrderRepository orders, RefundRepository refunds, RefundAttemptRepository attempts,
            ProcessedEventGuard inbox, OutboxRecorder outbox, EntityManager entityManager, Clock refundClock) {
        return new ApplyRefundRestoration(orders, refunds, attempts, inbox, outbox, entityManager, refundClock);
    }

    @Bean ApplyRefundPaymentConfirmation applyRefundPaymentConfirmation(OrderRepository orders, RefundRepository refunds,
            RefundAttemptRepository attempts, ProcessedEventGuard inbox, EntityManager entityManager) {
        return new ApplyRefundPaymentConfirmation(orders, refunds, attempts, inbox, entityManager);
    }

    @Bean
    Clock refundClock() {
        // 기존 Order·Payment와 DB DATETIME(6)의 시각 정밀도를 맞춘다. 초 단위로 절삭하지 않는다.
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }

    @Bean
    RequestRefund requestRefund(OrderRepository orders, RefundRepository refunds,
            OrderLicenseGrantRepository grants, OutboxRecorder outbox, Clock refundClock) {
        return new RequestRefund(orders, refunds, grants, outbox, refundClock);
    }

    @Bean
    ApplyLicenseGrant applyLicenseGrant(OrderRepository orders, OrderLicenseGrantRepository grants,
            RefundRepository refunds, ProcessedEventGuard inbox, OutboxRecorder outbox,
            EntityManager entityManager, Clock refundClock) {
        return new ApplyLicenseGrant(orders, grants, refunds, inbox, outbox, entityManager, refundClock);
    }

    @Bean
    ApplyRefundSuspension applyRefundSuspension(OrderRepository orders, RefundRepository refunds,
            OrderLicenseGrantRepository grants, RefundAttemptRepository attempts, ProcessedEventGuard inbox,
            OutboxRecorder outbox, EntityManager entityManager, Clock refundClock) {
        return new ApplyRefundSuspension(orders, refunds, grants, attempts, inbox, outbox, entityManager, refundClock);
    }
}
