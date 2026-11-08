package com.stove.settlement.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.settlement.adapter.CatalogOwnerHttpClient;
import com.stove.settlement.adapter.SimulatedPgPaymentHttpClient;
import com.stove.settlement.adapter.SimulatedPgSettlementHttpClient;
import com.stove.settlement.adapter.UnavailablePgRefunds;
import com.stove.settlement.core.domain.PgReceiptRepository;
import com.stove.settlement.core.domain.PgTransactionRepository;
import com.stove.settlement.core.domain.PgFeeRepository;
import com.stove.settlement.core.service.RecordPgTransaction;
import com.stove.settlement.core.service.SynchronizePgTransaction;
import com.stove.settlement.core.domain.PlatformFeePolicy;
import com.stove.settlement.core.domain.FullRefundAllocationPolicy;
import com.stove.settlement.core.domain.ReverseOriginalFullRefundPolicy;
import com.stove.settlement.core.domain.RefundSettlementMonthPolicy;
import com.stove.settlement.core.domain.UseConfirmedRefundMonthPolicy;
import com.stove.settlement.core.domain.SellerCarryoverRepository;
import com.stove.settlement.core.domain.SellerLockRepository;
import com.stove.settlement.core.domain.SellerMonthlySettlementRepository;
import com.stove.settlement.core.domain.SettlementSaleRepository;
import com.stove.settlement.core.port.CatalogOwners;
import com.stove.settlement.core.port.PgPayments;
import com.stove.settlement.core.port.PgRefunds;
import com.stove.settlement.core.domain.SettlementRefundRepository;
import com.stove.settlement.core.service.RecordConfirmedRefund;
import com.stove.settlement.core.port.PgSettlements;
import com.stove.settlement.core.service.CloseSellerMonth;
import com.stove.settlement.core.service.ReceivePgSettlement;
import com.stove.settlement.core.service.ReconcilePgReceipt;
import com.stove.settlement.core.service.RecordPaidSale;
import java.net.URI;
import java.time.Clock;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods=false)
public class SettlementConfiguration {
    @Bean Clock settlementClock() { return Clock.systemUTC(); }
    @Bean PlatformFeePolicy platformFeePolicy() { return new PlatformFeePolicy("PLATFORM_MVP_V1",1000); }
    @Bean FullRefundAllocationPolicy fullRefundAllocationPolicy() { return new ReverseOriginalFullRefundPolicy(); }
    @Bean RefundSettlementMonthPolicy refundSettlementMonthPolicy() { return new UseConfirmedRefundMonthPolicy(); }
    @Bean @ConditionalOnProperty(name="stove.settlement.pg-provider",havingValue="SIMULATED",matchIfMissing=true)
    PgPayments pgPayments(SettlementHttpProperties p, RestClient.Builder builder) {
        return new SimulatedPgPaymentHttpClient(client(p.pgBaseUrl(),"X-Pg-Api-Key",p.pgApiKey(),p,builder));
    }
    @Bean @ConditionalOnProperty(name="stove.settlement.pg-provider",havingValue="SIMULATED",matchIfMissing=true)
    PgSettlements pgSettlements(SettlementHttpProperties p, RestClient.Builder builder) {
        return new SimulatedPgSettlementHttpClient(client(p.pgBaseUrl(),"X-Pg-Api-Key",p.pgApiKey(),p,builder));
    }
    @Bean CatalogOwners catalogOwners(SettlementHttpProperties p, RestClient.Builder builder) {
        return new CatalogOwnerHttpClient(client(p.catalogBaseUrl(),"X-Settlement-Service-Key",p.catalogKey(),p,builder));
    }
    @Bean @ConditionalOnProperty(name="stove.settlement.pg-provider",havingValue="SIMULATED",matchIfMissing=true)
    PgRefunds pgRefunds() { return new UnavailablePgRefunds(); }
    @Bean RecordConfirmedRefund recordConfirmedRefund(SettlementRefundRepository refunds,SettlementSaleRepository sales,
            SellerMonthlySettlementRepository months,PgTransactionRepository records,PgRefunds supplier,ProcessedEventGuard inbox,
            FullRefundAllocationPolicy allocation,RefundSettlementMonthPolicy policy,Clock clock,PlatformTransactionManager manager,SellerLockRepository sellers) {
        return new RecordConfirmedRefund(refunds,sales,months,records,supplier,inbox,allocation,policy,clock,manager,sellers);
    }
    private RestClient client(URI uri,String header,String key,SettlementHttpProperties p, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(p.connectTimeout()); factory.setReadTimeout(p.readTimeout());
        return builder.clone().baseUrl(uri.toString()).defaultHeader(header,key).requestFactory(factory).build();
    }
    @Bean RecordPgTransaction recordPgTransaction(PgTransactionRepository transactions,PgFeeRepository fees) {
        return new RecordPgTransaction(transactions,fees);
    }
    @Bean SynchronizePgTransaction refreshPgTransaction(PgTransactionRepository records,PgFeeRepository fees,PgPayments payments,
            RecordPgTransaction recorder,Clock clock,PlatformTransactionManager transactions) {
        return new SynchronizePgTransaction(records,fees,payments,recorder,clock,transactions);
    }
    @Bean RecordPaidSale recordPaidSale(SettlementSaleRepository sales,SellerMonthlySettlementRepository months,ProcessedEventGuard guard,
            PgPayments payments,CatalogOwners owners,PlatformFeePolicy policy,Clock clock,PlatformTransactionManager transactions,RecordPgTransaction pgRecords,
            SellerLockRepository sellers) {
        return new RecordPaidSale(sales,months,guard,payments,owners,policy,clock,transactions,pgRecords,sellers);
    }
    @Bean CloseSellerMonth closeSellerMonth(SellerMonthlySettlementRepository months,SettlementSaleRepository sales,Clock clock,PlatformTransactionManager transactions,
            SettlementRefundRepository refunds,SellerCarryoverRepository carryovers,SellerLockRepository sellers) {
        return new CloseSellerMonth(months,sales,clock,transactions,refunds,carryovers,sellers);
    }
    @Bean ReconcilePgReceipt reconcilePgReceipt(PgReceiptRepository receipts,SettlementSaleRepository sales,Clock clock,PgTransactionRepository transactions,PgFeeRepository fees) {
        return new ReconcilePgReceipt(receipts,sales,clock,transactions,fees);
    }
    @Bean ReceivePgSettlement receivePgSettlement(PgReceiptRepository receipts,PgSettlements supplier,ReconcilePgReceipt reconcile,
            Clock clock,PlatformTransactionManager transactions) {
        return new ReceivePgSettlement(receipts,supplier,reconcile,clock,transactions);
    }
    @Bean Jackson2ObjectMapperBuilderCustomizer strictJson() {
        return builder -> builder.featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT,MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
