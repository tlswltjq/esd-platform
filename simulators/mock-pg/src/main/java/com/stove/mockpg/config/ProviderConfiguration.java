package com.stove.mockpg.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.stove.mockpg.core.service.ProcessProviderLedger;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
public class ProviderConfiguration {
    @Bean
    ProcessProviderLedger ledger(JdbcTemplate jdbc, PlatformTransactionManager transactions, MockPgProperties properties) {
        return new ProcessProviderLedger(jdbc, transactions, Clock.systemUTC(), properties.decision(),
                properties.feePolicyId(), properties.feeRateBps());
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictJson() {
        return builder -> builder.featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT, MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Bean
    OncePerRequestFilter clientKeys(MockPgProperties properties) {
        String payment = properties.paymentKey(); String settlement = properties.settlementKey(); String control = properties.controlKey();
        return new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String path = request.getRequestURI(); String method = request.getMethod();
                if (method.equals("GET") && (path.equals("/actuator/health") || path.startsWith("/actuator/health/"))) {
                    chain.doFilter(request, response); return;
                }
                String key = request.getHeader("X-Pg-Api-Key");
                boolean p = same(payment, key); boolean s = same(settlement, key); boolean c = same(control, key);
                if (!p && !s && !c) { response.sendError(401); return; }
                boolean allowed = p && method.equals("POST") && path.equals("/v1/charges")
                        || (p || s) && method.equals("GET") && path.startsWith("/v1/charges/by-reference/")
                        || s && method.equals("GET") && path.startsWith("/v1/settlements/")
                        || c && ((method.equals("POST") && path.equals("/test-support/v1/settlement-runs"))
                        || method.equals("GET") && path.startsWith("/test-support/v1/settlement-runs/"));
                if (!allowed) { response.sendError(403); return; }
                chain.doFilter(request, response);
            }
        };
    }

    private static boolean same(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
