package com.stove.license.config;

import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.license.core.domain.LicenseRepository;
import com.stove.license.core.domain.LicenseRestorationRepository;
import com.stove.license.core.service.ApplyLicenseRestoration;
import com.stove.license.core.domain.LicenseSuspensionRepository;
import com.stove.license.core.service.ApplyLicenseSuspension;
import jakarta.persistence.EntityManager;
import com.stove.license.core.domain.LicenseApprovalCorrectionRepository;
import com.stove.license.core.service.ApplyApprovalCorrection;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class LicenseSuspensionConfiguration {
    @Bean ApplyApprovalCorrection applyApprovalCorrection(LicenseRepository licenses, LicenseSuspensionRepository suspensions,
            LicenseApprovalCorrectionRepository corrections, ProcessedEventGuard inbox, OutboxRecorder outbox, EntityManager entityManager, Clock licenseSuspensionClock) {
        return new ApplyApprovalCorrection(licenses,suspensions,corrections,inbox,outbox,entityManager,licenseSuspensionClock);
    }
    @Bean ApplyLicenseRestoration applyLicenseRestoration(LicenseRepository licenses, LicenseSuspensionRepository suspensions,
            LicenseRestorationRepository restorations, ProcessedEventGuard inbox, OutboxRecorder outbox, EntityManager entityManager, Clock licenseSuspensionClock) {
        return new ApplyLicenseRestoration(licenses, suspensions, restorations, inbox, outbox, entityManager, licenseSuspensionClock);
    }

    @Bean
    Clock licenseSuspensionClock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }

    @Bean
    ApplyLicenseSuspension applyLicenseSuspension(LicenseRepository licenses,
            LicenseSuspensionRepository suspensions, ProcessedEventGuard inbox, OutboxRecorder outbox,
            EntityManager entityManager, Clock licenseSuspensionClock) {
        return new ApplyLicenseSuspension(licenses, suspensions, inbox, outbox, entityManager, licenseSuspensionClock);
    }
}
