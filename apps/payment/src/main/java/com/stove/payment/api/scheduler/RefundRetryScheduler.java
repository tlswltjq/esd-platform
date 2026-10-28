package com.stove.payment.api.scheduler;

import com.stove.payment.core.service.ResolveRefundRetry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name = "stove.payment.refund-retry.enabled", havingValue = "true")
public class RefundRetryScheduler {
    private final ResolveRefundRetry resolve;
    @Scheduled(fixedDelay = 1000, scheduler = "refundRetryTaskScheduler")
    public void run() { resolve.execute(); }
}
