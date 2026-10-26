package com.stove.payment.config;

import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.payment.adapter.trace.JdbcRefundExecutionTraceContexts;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.port.RefundExecutionTraceContexts;
import com.stove.payment.core.service.ReceiveRefundExecution;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class RefundReceptionConfiguration {
    @Bean Clock refundReceptionClock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }

    @Bean RefundExecutionTraceContexts refundExecutionTraceContexts(JdbcTemplate jdbc) {
        return new JdbcRefundExecutionTraceContexts(jdbc);
    }

    @Bean ReceiveRefundExecution receiveRefundExecution(PaymentRepository payments, RefundExecutionRepository executions,
            ProcessedEventGuard inbox, RefundExecutionTraceContexts contexts, Clock refundReceptionClock) {
        return new ReceiveRefundExecution(payments, executions, inbox, contexts, refundReceptionClock);
    }
}
