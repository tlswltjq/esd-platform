package com.stove.order.config;

import com.stove.common.security.ApiJwtSecurity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    @Order(1)
    SecurityFilterChain paymentInternalSecurity(HttpSecurity http,
            @Value("${stove.order.payment-service-key:}") String key) throws Exception {
        byte[] expected = key.getBytes(StandardCharsets.UTF_8);
        return http.securityMatcher("/internal/**").csrf(csrf -> csrf.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint((request, response, failure) -> response.setStatus(401)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/internal/v1/orders/*/payment-admissions",
                                "/internal/v1/orders/*/payment-admissions/*/failure")
                        .access((authentication, context) -> {
                            String provided = context.getRequest().getHeader("X-Payment-Service-Key");
                            return new AuthorizationDecision(!key.isBlank() && provided != null
                                    && MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8)));
                        }).anyRequest().denyAll()).build();
    }

    @Bean @Order(2) SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/refunds", "/api/v1/admin/refunds/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/refunds/*/decision").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/refunds/*/approval-stops").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/refunds/*/reviews").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/refunds/*/reconsiderations/*/decision").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders").hasRole("MEMBER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders/*/refund").hasRole("MEMBER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders/*/refund/retries").hasRole("MEMBER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders/*/refund/reconsiderations").hasRole("MEMBER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/orders/*/refund/reviews").hasRole("MEMBER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/orders/*/refund").hasRole("MEMBER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/orders", "/api/v1/orders/*").hasRole("MEMBER")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(ApiJwtSecurity.rolesConverter())))
                .build();
    }
    @Bean JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                               @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwks) {
        return ApiJwtSecurity.decoder(issuer, jwks);
    }
}
