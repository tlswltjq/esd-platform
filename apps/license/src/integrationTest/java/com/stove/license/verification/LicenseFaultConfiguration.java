package com.stove.license.verification;

import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.license.core.domain.LicenseRepository;
import com.stove.license.core.domain.PaidOrder;
import com.stove.license.core.service.GrantLicense;
import jakarta.persistence.EntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Explicit OCI source, absent from the production bootJar.
 */
@TestConfiguration(proxyBeanMethods = false)
public class LicenseFaultConfiguration {
    @Bean
    @Primary
    GrantLicense verificationGrant(LicenseRepository licenses,
                                   ProcessedEventGuard inbox, EntityManager entityManager,
                                   OutboxRecorder outbox, Environment env) {
        return new HaltAfterCommitGrant(licenses, inbox, entityManager, outbox,
                Path.of(env.getRequiredProperty("stove.verification.commit-marker")),
                env.getProperty("stove.verification.halt-after-commit", Boolean.class, false));
    }

    public static class HaltAfterCommitGrant extends GrantLicense {
        private final Path marker;
        private final boolean halt;

        HaltAfterCommitGrant(LicenseRepository licenses,
                             ProcessedEventGuard inbox, EntityManager entityManager,
                             OutboxRecorder outbox, Path marker, boolean halt) {
            super(licenses, inbox, entityManager, outbox);
            this.marker = marker;
            this.halt = halt;
        }

        @Override
        @Transactional(isolation = Isolation.READ_COMMITTED)
        public void execute(PaidOrder order) {
            try {
                Files.writeString(Path.of(marker + ".deliveries"), order.eventId() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            super.execute(order);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        Files.writeString(marker, order.eventId());
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                    if (halt) Runtime.getRuntime().halt(91);
                }
            });
        }
    }
}
