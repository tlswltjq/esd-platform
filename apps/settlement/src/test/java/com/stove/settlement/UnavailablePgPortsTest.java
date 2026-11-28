package com.stove.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.adapter.UnavailablePgPayments;
import com.stove.settlement.adapter.UnavailablePgRefunds;
import com.stove.settlement.adapter.UnavailablePgSettlements;
import com.stove.settlement.adapter.UnavailablePgStatementSource;
import com.stove.settlement.config.PgStatementConfiguration;
import com.stove.settlement.config.SettlementConfiguration;
import com.stove.settlement.config.SettlementHttpProperties;
import com.stove.settlement.core.domain.PgCostPostingRepository;
import com.stove.settlement.core.domain.PgFeeRepository;
import com.stove.settlement.core.domain.PgReceiptRepository;
import com.stove.settlement.core.domain.PgStatementEntryRepository;
import com.stove.settlement.core.domain.PgStatementItemRepository;
import com.stove.settlement.core.domain.PgStatementRepository;
import com.stove.settlement.core.domain.PgTransactionRepository;
import com.stove.settlement.core.domain.SellerCarryoverRepository;
import com.stove.settlement.core.domain.SellerLockRepository;
import com.stove.settlement.core.domain.SellerMonthlySettlementRepository;
import com.stove.settlement.core.domain.SettlementRefundRepository;
import com.stove.settlement.core.domain.SettlementSaleRepository;
import com.stove.settlement.core.port.PgPayments;
import com.stove.settlement.core.port.PgRefunds;
import com.stove.settlement.core.port.PgSettlements;
import com.stove.settlement.core.port.PgStatementSource;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

/** PG 연동을 미룬 동안(결정 remove-pg-adapters) 기본 조립은 PG 포트 넷을 모두 "제공 안 함" 어댑터로 채운다. */
class UnavailablePgPortsTest {
    @Test void defaultAssemblyFillsEveryPgPortWithAnUnavailableAdapterAndStartsNoStatementTimer() {
        ApplicationContextRunner runner=new ApplicationContextRunner()
                .withUserConfiguration(SettlementConfiguration.class,PgStatementConfiguration.class)
                .withBean(RestClient.Builder.class,RestClient::builder)
                .withBean(SettlementHttpProperties.class,() -> new SettlementHttpProperties(URI.create("http://catalog:8081"),"catalog-key",
                        Duration.ofSeconds(2),Duration.ofSeconds(5)));
        for(Class<?> collaborator:List.of(SettlementSaleRepository.class,SettlementRefundRepository.class,SellerMonthlySettlementRepository.class,
                SellerCarryoverRepository.class,SellerLockRepository.class,PgTransactionRepository.class,PgFeeRepository.class,PgReceiptRepository.class,
                PgCostPostingRepository.class,PgStatementRepository.class,PgStatementEntryRepository.class,PgStatementItemRepository.class,
                ProcessedEventGuard.class,PlatformTransactionManager.class)) runner=mocked(runner,collaborator);
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PgPayments.class).hasSingleBean(PgSettlements.class)
                    .hasSingleBean(PgRefunds.class).hasSingleBean(PgStatementSource.class).doesNotHaveBean("pgStatementSchedule");
            assertThat(context.getBean(PgPayments.class)).isInstanceOf(UnavailablePgPayments.class);
            assertThat(context.getBean(PgSettlements.class)).isInstanceOf(UnavailablePgSettlements.class);
            assertThat(context.getBean(PgRefunds.class)).isInstanceOf(UnavailablePgRefunds.class);
            assertThat(context.getBean(PgStatementSource.class)).isInstanceOf(UnavailablePgStatementSource.class);
        });
    }
    @Test void everyUnavailableAdapterRejectsInsteadOfInventingEvidence() {
        assertUnavailable(() -> new UnavailablePgPayments().require("payment-1"));
        assertUnavailable(() -> new UnavailablePgSettlements().require("settlement-1"));
        assertUnavailable(() -> new UnavailablePgRefunds().require(null,null));
        assertUnavailable(() -> new UnavailablePgStatementSource().fetch(PgStatementFixtures.query()));
    }
    private <T> ApplicationContextRunner mocked(ApplicationContextRunner runner,Class<T> type) {
        return runner.withBean(type,() -> mock(type));
    }
    private void assertUnavailable(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE));
    }
}
