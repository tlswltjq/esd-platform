package com.stove.payment.api.scheduler;

import com.stove.payment.core.service.ResolvePaymentApproval;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${stove.payment.pg.provider:SIMULATED}' == 'PORTONE_TEST' && ${stove.payment.processing-enabled:true}")
public class PaymentApprovalScheduler {
    private final ResolvePaymentApproval resolve;
    public PaymentApprovalScheduler(ResolvePaymentApproval resolve) { this.resolve=resolve; }
    @Scheduled(fixedDelay=1000,scheduler="paymentApprovalTaskScheduler")
    public void run() { resolve.execute(); }
}
