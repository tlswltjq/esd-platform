package com.stove.settlement.config;

import com.stove.settlement.api.application.CreatorWorkspaceClient;
import com.stove.settlement.core.domain.FeePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class SettlementConfiguration {
    @Bean
    FeePolicy feePolicy(SettlementProperties properties) {
        return new FeePolicy(properties.selfSellerId(), properties.partnerFeeRate());
    }

    @Bean
    CreatorWorkspaceClient creatorWorkspaceClient(RestClient.Builder builder,
            @Value("${stove.settlement.studio-url:http://localhost:8085}") String url) {
        return new CreatorWorkspaceClient(builder.baseUrl(url).build());
    }
}
