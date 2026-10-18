package com.stove.settlement.config.verification;

import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.settlement.core.domain.PaidOrder;
import com.stove.settlement.core.domain.PlatformFeePolicy;
import com.stove.settlement.core.domain.SellerMonthlySettlementRepository;
import com.stove.settlement.core.domain.SettlementSale;
import com.stove.settlement.core.domain.SettlementSaleRepository;
import com.stove.settlement.core.port.CatalogOwners;
import com.stove.settlement.core.port.PgPayments;
import com.stove.settlement.core.service.RecordPaidSale;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

@TestConfiguration(proxyBeanMethods=false)
public class SettlementFaultConfiguration {
    @Bean @Primary
    RecordPaidSale commitBeforeAckRecordPaidSale(SettlementSaleRepository sales,SellerMonthlySettlementRepository months,
            ProcessedEventGuard guard,PgPayments payments,CatalogOwners owners,PlatformFeePolicy policy,Clock clock,
            PlatformTransactionManager transactions,@Value("${stove.verification.commit-marker}") Path marker) {
        return new CommitBeforeAck(sales,months,guard,payments,owners,policy,clock,transactions,marker);
    }

    /** Halt only after the business transaction commits, before the listener can return and acknowledge. */
    static final class CommitBeforeAck extends RecordPaidSale {
        private final Path marker;
        CommitBeforeAck(SettlementSaleRepository sales,SellerMonthlySettlementRepository months,ProcessedEventGuard guard,
                PgPayments payments,CatalogOwners owners,PlatformFeePolicy policy,Clock clock,PlatformTransactionManager transactions,Path marker) {
            super(sales,months,guard,payments,owners,policy,clock,transactions);this.marker=marker;
        }
        @Override public SettlementSale execute(PaidOrder input) {
            SettlementSale saved=super.execute(input);
            try { Files.writeString(marker,input.eventId()); }
            catch(IOException failure) { throw new IllegalStateException("Cannot record committed-event fixture",failure); }
            Runtime.getRuntime().halt(0);
            return saved;
        }
    }
}
