package com.stove.store.config;

import com.stove.store.infrastructure.CatalogHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class CatalogClientConfiguration {
    @Bean
    public CatalogHttpClient catalogProducts(@Value("${stove.store.catalog-base-url}") String baseUrl,
            ObjectMapper mapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        RestClient client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        return new CatalogHttpClient(client, mapper);
    }
}
