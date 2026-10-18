package com.stove.settlement.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.settlement.adapter.CatalogOwnerHttpClient;
import com.stove.settlement.adapter.SimulatedPgPaymentHttpClient;
import com.stove.settlement.adapter.SimulatedPgSettlementHttpClient;
import com.stove.settlement.core.domain.PgReceiptRepository;
import com.stove.settlement.core.domain.PlatformFeePolicy;
import com.stove.settlement.core.domain.SellerMonthlySettlementRepository;
import com.stove.settlement.core.domain.SettlementSaleRepository;
import com.stove.settlement.core.port.CatalogOwners;
import com.stove.settlement.core.port.PgPayments;
import com.stove.settlement.core.port.PgSettlements;
import com.stove.settlement.core.service.CloseSellerMonth;
import com.stove.settlement.core.service.ReceivePgSettlement;
import com.stove.settlement.core.service.ReconcilePgReceipt;
import com.stove.settlement.core.service.RecordPaidSale;
import java.net.URI;
import java.time.Clock;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods=false)
public class SettlementConfiguration {
    @Bean Clock settlementClock() { return Clock.systemUTC(); }
    @Bean PlatformFeePolicy platformFeePolicy() { return new PlatformFeePolicy("PLATFORM_MVP_V1",1000); }
    @Bean PgPayments pgPayments(SettlementHttpProperties p) {
        return new SimulatedPgPaymentHttpClient(client(p.pgBaseUrl(),"X-Pg-Api-Key",p.pgApiKey(),p));
    }
    @Bean PgSettlements pgSettlements(SettlementHttpProperties p) {
        return new SimulatedPgSettlementHttpClient(client(p.pgBaseUrl(),"X-Pg-Api-Key",p.pgApiKey(),p));
    }
    @Bean CatalogOwners catalogOwners(SettlementHttpProperties p) {
        return new CatalogOwnerHttpClient(client(p.catalogBaseUrl(),"X-Settlement-Service-Key",p.catalogKey(),p));
    }
    private RestClient client(URI uri,String header,String key,SettlementHttpProperties p) {
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(p.connectTimeout()); factory.setReadTimeout(p.readTimeout());
        return RestClient.builder().baseUrl(uri.toString()).defaultHeader(header,key).requestFactory(factory).build();
    }
    @Bean RecordPaidSale recordPaidSale(SettlementSaleRepository sales,SellerMonthlySettlementRepository months,ProcessedEventGuard guard,
            PgPayments payments,CatalogOwners owners,PlatformFeePolicy policy,Clock clock,PlatformTransactionManager transactions) {
        return new RecordPaidSale(sales,months,guard,payments,owners,policy,clock,transactions);
    }
    @Bean CloseSellerMonth closeSellerMonth(SellerMonthlySettlementRepository months,SettlementSaleRepository sales,Clock clock,PlatformTransactionManager transactions) {
        return new CloseSellerMonth(months,sales,clock,transactions);
    }
    @Bean ReconcilePgReceipt reconcilePgReceipt(PgReceiptRepository receipts,SettlementSaleRepository sales,Clock clock) {
        return new ReconcilePgReceipt(receipts,sales,clock);
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
