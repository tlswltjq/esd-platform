package com.stove.settlement.config;

import com.stove.settlement.adapter.PortOnePaymentHttpClient;
import com.stove.settlement.adapter.UnavailablePgSettlements;
import com.stove.settlement.core.port.PgPayments;
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
        properties.requireConfigured();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout()); factory.setReadTimeout(properties.readTimeout());
        RestClient client = builder.clone().baseUrl(properties.baseUrl().toString())
                .defaultHeader("Authorization", "PortOne " + properties.apiSecret()).requestFactory(factory).build();
        return new PortOnePaymentHttpClient(client, properties.storeId(), properties.channelKey());
    }
    @Bean PgSettlements portOnePgSettlements() { return new UnavailablePgSettlements(); }
}
