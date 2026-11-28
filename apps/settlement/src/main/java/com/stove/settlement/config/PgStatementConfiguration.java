package com.stove.settlement.config;

import com.stove.settlement.adapter.UnavailablePgStatementSource;
import com.stove.settlement.core.domain.PgStatementEntryRepository;
import com.stove.settlement.core.domain.PgCostPostingRepository;
import com.stove.settlement.core.domain.PgStatementItemRepository;
import com.stove.settlement.core.domain.PgStatementRepository;
import com.stove.settlement.core.domain.PgTransactionRepository;
import com.stove.settlement.core.domain.SettlementRefundRepository;
import com.stove.settlement.core.domain.SettlementSaleRepository;
import com.stove.settlement.core.port.PgStatementSource;
import com.stove.settlement.core.service.ReceivePgStatement;
import com.stove.settlement.core.service.ReconcilePendingPgStatements;
import com.stove.settlement.core.service.ReconcilePgStatement;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
public class PgStatementConfiguration {
    // PG 연동을 미룬 동안 정산 자료 출처는 "제공 안 함" 어댑터다(결정 remove-pg-adapters). 주기 수집도 조립하지 않는다.
    @Bean PgStatementSource pgStatementSource() {return new UnavailablePgStatementSource();}
    @Bean ReconcilePgStatement reconcilePgStatement(PgStatementRepository statements,PgStatementEntryRepository entries,PgStatementItemRepository items,
            PgTransactionRepository transactions,SettlementSaleRepository sales,SettlementRefundRepository refunds,Clock clock,PgCostPostingRepository postings) {
        return new ReconcilePgStatement(statements,entries,items,transactions,sales,refunds,clock,postings);
    }
    @Bean ReconcilePendingPgStatements reconcilePendingPgStatements(PgStatementRepository statements,ReconcilePgStatement reconcile) {
        return new ReconcilePendingPgStatements(statements,reconcile);
    }
    @Bean ReceivePgStatement receivePgStatement(PgStatementSource source,PgStatementRepository statements,PgStatementEntryRepository entries,
            PgStatementItemRepository items,ReconcilePgStatement reconcile,Clock clock,PlatformTransactionManager manager) {
        return new ReceivePgStatement(source,statements,entries,items,reconcile,clock,manager);
    }
}
