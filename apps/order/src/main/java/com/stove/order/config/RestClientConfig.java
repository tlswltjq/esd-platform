package com.stove.order.config;

import com.stove.common.web.CorrelationIdFilter;
import com.stove.order.infrastructure.client.CatalogProperties;
import org.slf4j.MDC;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 서비스 간 동기 호출용 RestClient.
 * 타임아웃을 반드시 명시해 상류 지연이 주문 스레드를 잠식하지 않게 한다.
 * 상관관계 ID를 헤더로 전파해 서비스 경계를 넘는 로그 추적을 가능하게 한다.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient catalogRestClient(RestClient.Builder builder, CatalogProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout());

        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .requestInterceptor((request, body, execution) -> {
                    String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
                    if (correlationId != null) {
                        request.getHeaders().add(CorrelationIdFilter.HEADER, correlationId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }
}
