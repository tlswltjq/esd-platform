package com.stove.payment.config;

import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.payment.core.domain.RefundClosureRepository;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.port.RefundPaymentLookup;
import com.stove.payment.core.port.RefundCancellationGateway;
import com.stove.payment.core.service.CloseRefundDelay;
import com.stove.payment.core.service.ReconcileRefundPayment;
import com.stove.payment.core.service.RequestRefundCancellation;
import com.stove.payment.core.service.RecordRefundPreparation;
import com.stove.payment.core.service.RecordRefundCancellationSuccess;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentApprovalRepository;
import com.stove.payment.core.domain.RefundCancellationHoldRepository;
import com.stove.payment.core.port.PortOneWebhookReceipts;
import com.stove.payment.core.service.ReceivePortOneWebhook;
import com.stove.payment.core.service.ReadPayment;
import com.stove.payment.core.service.ReadPaymentCheckout;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "stove.payment.pg.provider", havingValue = "PORTONE_TEST")
public class PortOneConfiguration {
    // 결정 remove-pg-adapters: PaymentAttemptLookup·RefundCancellationGateway·RefundPaymentLookup·PortOneWebhookVerifier·PortOneWebhookReceipts 포트는 운영 구현이 없다.
    @Bean RequestRefundCancellation requestRefundCancellation(PaymentRepository payments, RefundExecutionRepository executions,
            RefundCancellationGateway gateway, ReconcileRefundPayment reconcile, PlatformTransactionManager transactions,
            Clock refundReceptionClock, RecordRefundCancellationSuccess successes, PortOneProperties properties,
            RefundCancellationHoldRepository holds, RecordRefundPreparation preparationEvents) {
        Duration lease = properties.connectTimeout().plus(properties.readTimeout()).multipliedBy(3).plusSeconds(30);
        return new RequestRefundCancellation(payments, executions, gateway, reconcile, transactions, refundReceptionClock, successes, lease, holds, preparationEvents);
    }

    @Bean RecordRefundCancellationSuccess recordRefundCancellationSuccess(PaymentRepository payments, RefundExecutionRepository executions,
            OutboxRecorder outbox, PlatformTransactionManager transactions, Clock refundReceptionClock) {
        return new RecordRefundCancellationSuccess(payments, executions, outbox, transactions, refundReceptionClock);
    }

    /** The operator closure takes the lookup worker's lease (decision 127), so both use one lease length. */
    @Bean CloseRefundDelay closeRefundDelay(PaymentRepository payments, RefundExecutionRepository executions, RefundClosureRepository closures,
            RefundPaymentLookup lookup, OutboxRecorder outbox, PlatformTransactionManager transactions, Clock refundReceptionClock,
            PortOneProperties properties) {
        return new CloseRefundDelay(payments, executions, closures, lookup, outbox, refundReceptionClock,
                RefundLookupConfiguration.lookupLease(properties), transactions);
    }

    @Bean ReconcileRefundPayment reconcileRefundPayment(PaymentRepository payments, RefundExecutionRepository executions,
            RefundPaymentLookup lookup, OutboxRecorder outbox, PlatformTransactionManager transactions, Clock refundReceptionClock) {
        return new ReconcileRefundPayment(payments, executions, lookup, outbox, transactions, refundReceptionClock);
    }

    @Bean ReadPaymentCheckout readPaymentCheckout(ReadPayment readPayment, PortOneProperties properties,PaymentApprovalRepository approvals,Clock refundReceptionClock) {
        properties.requireConfigured();
        return new ReadPaymentCheckout(readPayment, properties.storeId(), properties.channelKey(), properties.webhookUrl(),approvals,refundReceptionClock);
    }

    @Bean ReceivePortOneWebhook receivePortOneWebhook(PaymentRepository payments, PortOneWebhookReceipts receipts,
            Clock refundReceptionClock, RefundExecutionRepository executions) {
        return new ReceivePortOneWebhook(payments, receipts, refundReceptionClock, executions);
    }
}
