package com.stove.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.stove.settlement.adapter.TossPayStatementHttpClient;
import com.stove.settlement.adapter.UnavailablePgStatementSource;
import com.stove.settlement.config.PgStatementConfiguration;
import com.stove.settlement.config.TossPayStatementProperties;
import com.stove.settlement.core.domain.PgStatementEntryRepository;
import com.stove.settlement.core.domain.PgCostPostingRepository;
import com.stove.settlement.core.domain.PgStatementItemRepository;
import com.stove.settlement.core.domain.PgStatementRepository;
import com.stove.settlement.core.domain.PgTransactionRepository;
import com.stove.settlement.core.domain.SettlementRefundRepository;
import com.stove.settlement.core.domain.SettlementSaleRepository;
import com.stove.settlement.core.port.PgStatementSource;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

class PgStatementConfigurationTest {
    ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(PgStatementConfiguration.class)
                .withBean(PgCostPostingRepository.class,() -> mock(PgCostPostingRepository.class))
                .withBean(PgStatementRepository.class,() -> mock(PgStatementRepository.class))
                .withBean(PgStatementEntryRepository.class,() -> mock(PgStatementEntryRepository.class))
                .withBean(PgStatementItemRepository.class,() -> mock(PgStatementItemRepository.class))
                .withBean(PgTransactionRepository.class,() -> mock(PgTransactionRepository.class))
                .withBean(SettlementSaleRepository.class,() -> mock(SettlementSaleRepository.class))
                .withBean(SettlementRefundRepository.class,() -> mock(SettlementRefundRepository.class))
                .withBean(PlatformTransactionManager.class,() -> mock(PlatformTransactionManager.class))
                .withBean(Clock.class,() -> Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"),ZoneOffset.UTC));
    }
    @Test void disabledCollectionNeedsNoSecretAndDoesNotStartATimer() {
        runner().run(context -> {
            assertThat(context).hasSingleBean(PgStatementSource.class).doesNotHaveBean("pgStatementSchedule");
            assertThat(context.getBean(PgStatementSource.class)).isInstanceOf(UnavailablePgStatementSource.class);
        });
    }
    @Test void explicitlyEnabledCollectionAssemblesItsHttpSourceAndManagedTimer() {
        runner().withPropertyValues("stove.settlement.tosspay.enabled=true","stove.settlement.tosspay.scheduled-enabled=true")
                .withBean(RestClient.Builder.class,RestClient::builder)
                .withBean(TossPayStatementProperties.class,() -> new TossPayStatementProperties(true,URI.create("http://127.0.0.1:1"),
                        "sk_live_contract-fixture-only","merchant","LIVE","store","channel",false,10,
                        Duration.ofSeconds(1),Duration.ofSeconds(2),true,Duration.ofHours(1),7))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasBean("pgStatementSchedule");
                    assertThat(context.getBean(PgStatementSource.class)).isInstanceOf(TossPayStatementHttpClient.class);
                    ScheduledFuture<?> schedule=context.getBean("pgStatementSchedule",ScheduledFuture.class);
                    assertThat(schedule.isCancelled()).isFalse();
                });
    }
}
