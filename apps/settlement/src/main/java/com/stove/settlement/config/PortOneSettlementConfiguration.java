package com.stove.settlement.config;

import com.stove.settlement.adapter.PortOnePaymentHttpClient;
import com.stove.settlement.adapter.PortOneRefundHttpClient;
import com.stove.settlement.adapter.UnavailablePgSettlements;
import com.stove.settlement.core.port.PgPayments;
import com.stove.settlement.core.port.PgRefunds;
import com.stove.settlement.core.port.PgSettlements;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "stove.settlement.pg-provider", havingValue = "PORTONE_TEST")
public class PortOneSettlementConfiguration {
    @Bean PgPayments portOnePgPayments(PortOneSettlementProperties properties, RestClient.Builder builder) {
        return new PortOnePaymentHttpClient(client(properties,builder),properties.storeId(),properties.channelKey());
    }
    @Bean PgRefunds portOnePgRefunds(PortOneSettlementProperties properties,RestClient.Builder builder) {
        return new PortOneRefundHttpClient(client(properties,builder),properties.storeId(),properties.channelKey());
    }
    private RestClient client(PortOneSettlementProperties properties,RestClient.Builder builder) {
        properties.requireConfigured();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout()); factory.setReadTimeout(properties.readTimeout());
        return builder.clone().baseUrl(properties.baseUrl().toString())
                .defaultHeader("Authorization", "PortOne " + properties.apiSecret()).requestFactory(factory).build();
    }
    @Bean PgSettlements portOnePgSettlements() { return new UnavailablePgSettlements(); }
}
