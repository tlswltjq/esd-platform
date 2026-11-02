package com.stove.order.config;

import com.stove.order.adapter.CatalogHttpClient;
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
    public CatalogHttpClient catalogProducts(@Value("${stove.order.catalog-base-url}") String baseUrl,
            @Value("${stove.order.catalog-service-key:}") String serviceKey,
            @Value("${stove.order.catalog-connect-timeout-ms:3000}") int connectTimeout,
            @Value("${stove.order.catalog-read-timeout-ms:10000}") int readTimeout, RestClient.Builder builder) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeout))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(readTimeout));
        RestClient client = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
        return new CatalogHttpClient(client, serviceKey);
    }
}
