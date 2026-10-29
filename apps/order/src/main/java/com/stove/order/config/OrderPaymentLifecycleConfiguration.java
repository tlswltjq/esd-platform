package com.stove.order.config;

import com.stove.order.core.domain.OrderPaymentAdmissionRepository;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.service.CloseExpiredOrder;
import com.stove.order.core.service.RecordOrderPaymentAttemptFailure;
import com.stove.order.core.service.RequestOrderPaymentProcessing;
import com.stove.order.core.service.CloseExpiredOrders;
import java.time.Clock;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.order.core.service.RecordOrderExpiry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OrderPaymentLifecycleConfiguration {
    @Bean RecordOrderExpiry recordOrderExpiry(OutboxRecorder outbox) { return new RecordOrderExpiry(outbox); }
    @Bean RequestOrderPaymentProcessing requestOrderPaymentProcessing(OrderRepository orders,
            OrderPaymentAdmissionRepository admissions, Clock clock,RecordOrderExpiry expiry) {
        return new RequestOrderPaymentProcessing(orders, admissions, clock,expiry);
    }
    @Bean RecordOrderPaymentAttemptFailure recordOrderPaymentAttemptFailure(OrderRepository orders,
            OrderPaymentAdmissionRepository admissions,Clock clock,RecordOrderExpiry expiry) {
        return new RecordOrderPaymentAttemptFailure(orders,admissions,clock,expiry);
    }
    @Bean CloseExpiredOrders closeExpiredOrders(OrderRepository orders,CloseExpiredOrder close,Clock clock) {
        return new CloseExpiredOrders(orders,close,clock,100);
    }
    @Bean CloseExpiredOrder closeExpiredOrder(OrderRepository orders, Clock clock,RecordOrderExpiry expiry) {
        return new CloseExpiredOrder(orders, clock,expiry);
    }
    @Configuration(proxyBeanMethods=false)
    @EnableScheduling
    @ConditionalOnProperty(name="stove.order.expiry-enabled",havingValue="true",matchIfMissing=true)
    static class Scheduling {
        @Bean ThreadPoolTaskScheduler orderExpiryTaskScheduler() {
            ThreadPoolTaskScheduler scheduler=new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("order-expiry-"); return scheduler;
        }
    }
}
