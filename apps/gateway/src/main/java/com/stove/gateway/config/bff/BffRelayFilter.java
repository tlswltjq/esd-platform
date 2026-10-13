package com.stove.gateway.config.bff;

import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

@Configuration
public class BffRelayFilter {
    @Bean
    GlobalFilter bffTokenRelay() {
        return (exchange, chain) -> {
            String access = exchange.getAttribute(BffAuthenticationFilter.ACCESS_TOKEN);
            if (access == null) return chain.filter(exchange);
            return chain.filter(exchange.mutate().request(exchange.getRequest().mutate()
                    .headers(headers -> {
                        headers.setBearerAuth(access);
                        headers.remove(HttpHeaders.COOKIE);
                    }).build()).build());
        };
    }
}
