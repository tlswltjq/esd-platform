package com.stove.order.config;

import com.stove.order.infrastructure.CatalogHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class CatalogClientConfiguration {
    @Bean
    public CatalogHttpClient catalogProducts(@Value("${stove.order.catalog-base-url}") String baseUrl,
            @Value("${stove.order.catalog-connect-timeout-ms:3000}") int connectTimeout,
            @Value("${stove.order.catalog-read-timeout-ms:10000}") int readTimeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        var client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        return new CatalogHttpClient(client);
    }
}
