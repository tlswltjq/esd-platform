package com.stove.settlement.config;

import com.stove.settlement.adapter.TossPayStatementHttpClient;
import com.stove.settlement.adapter.UnavailablePgStatementSource;
import com.stove.settlement.core.domain.PgStatementEntryRepository;
import com.stove.settlement.core.domain.PgStatementItemRepository;
import com.stove.settlement.core.domain.PgStatementRepository;
import com.stove.settlement.core.domain.PgStatementScope;
import com.stove.settlement.core.domain.PgTransactionRepository;
import com.stove.settlement.core.domain.SettlementRefundRepository;
import com.stove.settlement.core.domain.SettlementSaleRepository;
import com.stove.settlement.core.port.PgStatementSource;
import com.stove.settlement.core.service.ReceivePgStatement;
import com.stove.settlement.core.service.SynchronizePgStatements;
import com.stove.settlement.core.service.ReconcilePgStatement;
import java.time.Clock;
import java.util.concurrent.ScheduledFuture;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods=false)
public class PgStatementConfiguration {
    @Bean @ConditionalOnProperty(prefix="stove.settlement.tosspay",name={"enabled","scheduled-enabled"},havingValue="true")
    ThreadPoolTaskScheduler pgStatementTaskScheduler() {
        ThreadPoolTaskScheduler scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("pg-statement-");return scheduler;
    }
    @Bean(destroyMethod="")
    @ConditionalOnProperty(prefix="stove.settlement.tosspay",name={"enabled","scheduled-enabled"},havingValue="true")
    ScheduledFuture<?> pgStatementSchedule(ReceivePgStatement collect,Clock clock,TossPayStatementProperties properties,ThreadPoolTaskScheduler scheduler) {
        return scheduler.scheduleWithFixedDelay(new SynchronizePgStatements(collect,clock,properties.lookbackDays()),properties.pollDelay());
    }
    @Bean @ConditionalOnProperty(prefix="stove.settlement.tosspay",name="enabled",havingValue="false",matchIfMissing=true)
    PgStatementSource unavailablePgStatementSource() {return new UnavailablePgStatementSource();}
    @Bean @ConditionalOnProperty(prefix="stove.settlement.tosspay",name="enabled",havingValue="true")
    PgStatementSource tossPayStatementSource(TossPayStatementProperties properties,RestClient.Builder builder) {
        properties.requireConfigured();SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());factory.setReadTimeout(properties.readTimeout());
        RestClient client=builder.clone().baseUrl(properties.baseUrl().toString()).requestFactory(factory).build();
        return new TossPayStatementHttpClient(client,properties.apiKey(),new PgStatementScope(properties.merchantId(),properties.environment(),
                properties.portoneStoreId(),properties.portoneChannelKey(),properties.identityMappingVerified()),properties.maxPages());
    }
    @Bean ReconcilePgStatement reconcilePgStatement(PgStatementRepository statements,PgStatementEntryRepository entries,PgStatementItemRepository items,
            PgTransactionRepository transactions,SettlementSaleRepository sales,SettlementRefundRepository refunds,Clock clock) {
        return new ReconcilePgStatement(statements,entries,items,transactions,sales,refunds,clock);
    }
    @Bean ReceivePgStatement receivePgStatement(PgStatementSource source,PgStatementRepository statements,PgStatementEntryRepository entries,
            PgStatementItemRepository items,ReconcilePgStatement reconcile,Clock clock,PlatformTransactionManager manager) {
        return new ReceivePgStatement(source,statements,entries,items,reconcile,clock,manager);
    }
}
