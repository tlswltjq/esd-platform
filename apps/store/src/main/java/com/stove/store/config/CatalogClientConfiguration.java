package com.stove.store.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.store.adapter.CatalogHttpClient;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class CatalogClientConfiguration {
    @Bean
    public CatalogHttpClient catalogProducts(@Value("${stove.store.catalog-base-url}") String baseUrl,
            @Value("${stove.store.catalog-service-key:}") String serviceKey, ObjectMapper mapper) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        RestClient client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        return new CatalogHttpClient(client, mapper, serviceKey);
    }
}
