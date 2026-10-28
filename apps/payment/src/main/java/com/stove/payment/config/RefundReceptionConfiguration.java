package com.stove.payment.config;

import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.payment.adapter.trace.JdbcRefundExecutionTraceContexts;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.port.RefundExecutionTraceContexts;
import com.stove.payment.core.service.ReceiveRefundExecution;
import com.stove.payment.core.service.RecordRefundCancellationPreparation;
import com.stove.payment.core.service.RequestRefundCancellationResume;
import com.stove.payment.core.service.ReadRefundCancellationHolds;
import com.stove.payment.core.domain.RefundCancellationHoldRepository;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.payment.core.service.RecordRefundPreparation;
import com.stove.payment.core.service.ReadRefundDelays;
import com.stove.payment.core.service.RecordRefundInvestigation;
import com.stove.payment.core.domain.RefundInvestigationRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class RefundReceptionConfiguration {
    @Bean ReadRefundDelays readRefundDelays(PaymentRepository payments, RefundExecutionRepository executions,
            RefundCancellationHoldRepository holds, RefundInvestigationRepository investigations, Clock refundReceptionClock) {
        return new ReadRefundDelays(payments, executions, holds, investigations, refundReceptionClock);
    }

    @Bean RecordRefundInvestigation recordRefundInvestigation(PaymentRepository payments, RefundExecutionRepository executions,
            RefundInvestigationRepository investigations, Clock refundReceptionClock) {
        return new RecordRefundInvestigation(payments, executions, investigations, refundReceptionClock);
    }

    @Bean RecordRefundPreparation recordRefundPreparation(OutboxRecorder outbox) { return new RecordRefundPreparation(outbox); }

    @Bean ReadRefundCancellationHolds readRefundCancellationHolds(PaymentRepository payments, RefundExecutionRepository executions,
            RefundCancellationHoldRepository holds, Clock refundReceptionClock) {
        return new ReadRefundCancellationHolds(payments, executions, holds, refundReceptionClock);
    }
    @Bean Clock refundReceptionClock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }

    @Bean RequestRefundCancellationResume requestRefundCancellationResume(PaymentRepository payments, RefundExecutionRepository executions,
            RefundCancellationHoldRepository holds, Clock refundReceptionClock) {
        return new RequestRefundCancellationResume(payments, executions, holds, refundReceptionClock);
    }

    @Bean RefundExecutionTraceContexts refundExecutionTraceContexts(JdbcTemplate jdbc) {
        return new JdbcRefundExecutionTraceContexts(jdbc);
    }

    @Bean ReceiveRefundExecution receiveRefundExecution(PaymentRepository payments, RefundExecutionRepository executions,
            ProcessedEventGuard inbox, RefundExecutionTraceContexts contexts, Clock refundReceptionClock) {
        return new ReceiveRefundExecution(payments, executions, inbox, contexts, refundReceptionClock);
    }

    @Bean RecordRefundCancellationPreparation recordRefundCancellationPreparation(RefundExecutionRepository executions,
            PlatformTransactionManager transactions, Clock refundReceptionClock, RecordRefundPreparation preparationEvents) {
        return new RecordRefundCancellationPreparation(executions, transactions, refundReceptionClock, preparationEvents);
    }
}
