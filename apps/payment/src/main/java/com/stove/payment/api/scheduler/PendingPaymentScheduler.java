package com.stove.payment.api.scheduler;

import com.stove.payment.core.service.ResolvePayment;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor @EnableScheduling
@ConditionalOnProperty(name="stove.payment.processing-enabled",havingValue="true",matchIfMissing=true)
public class PendingPaymentScheduler {
    private final ResolvePayment resolvePayment;
    @Scheduled(fixedDelayString="${stove.payment.poll-interval-ms:1000}")
    public void run() { resolvePayment.execute(); }
}
