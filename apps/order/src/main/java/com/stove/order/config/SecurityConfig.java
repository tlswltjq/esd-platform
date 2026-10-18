package com.stove.order.config;

import com.stove.common.security.ApiJwtSecurity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders").hasRole("MEMBER")
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
