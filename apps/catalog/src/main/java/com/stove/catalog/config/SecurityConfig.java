package com.stove.catalog.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    @Order(1)
    SecurityFilterChain internalBuildSecurity(HttpSecurity http,
            @Value("${stove.catalog.download-service-key:}") String key) throws Exception {
        byte[] expected = key.getBytes(StandardCharsets.UTF_8);
        return http.securityMatcher("/internal/**").csrf(csrf -> csrf.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint((request, response, failure) -> response.setStatus(401)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/internal/v1/products/*/build")
                        .access((authentication, context) -> {
                            String provided = context.getRequest().getHeader("X-Download-Service-Key");
                            return new AuthorizationDecision(!key.isBlank() && provided != null
                                    && MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8)));
                        }).anyRequest().denyAll()).build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/products", "/api/v1/products/by-code/*").permitAll()
                        .anyRequest().denyAll()).build();
    }
}
