package com.stove.payment.api.scheduler;

import com.stove.payment.core.service.ResolveRefundLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name = "stove.payment.refund-lookup.enabled", havingValue = "true")
public class RefundLookupScheduler {
    private final ResolveRefundLookup resolve;
    @Scheduled(fixedDelay = 1000, scheduler = "refundLookupTaskScheduler")
    public void run() { resolve.execute(); }
}
