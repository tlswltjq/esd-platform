package com.stove.studio.config;

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
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers(HttpMethod.GET, "/actuator/prometheus")
                                        .permitAll()
                                        .requestMatchers(
                                                "/actuator/health",
                                                "/actuator/metrics/**",
                                                "/v3/api-docs/**",
                                                "/swagger-ui/**")
                                        .permitAll()
                                        // 공통 운영 API는 기본 비활성이다.
                                        // 명시적으로 켠 인스턴스에서도 단건 재처리만 ADMIN에게 연다.
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/v1/ops/dlt",
                                                "/api/v1/ops/outbox/dead")
                                        .hasRole("ADMIN")
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/v1/ops/dlt/replay",
                                                "/api/v1/ops/outbox/dead/*/requeue")
                                        .hasRole("ADMIN")
                                        .requestMatchers(HttpMethod.GET, "/api/v1/studio/assets/**")
                                        .permitAll()
                                        .requestMatchers("/api/v1/studio/**")
                                        .hasRole("CREATOR")
                                        .anyRequest()
                                        .denyAll())
                .oauth2ResourceServer(
                        o ->
                                o.jwt(
                                        j ->
                                                j.jwtAuthenticationConverter(
                                                        ApiJwtSecurity.rolesConverter())))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwks) {
        return ApiJwtSecurity.decoder(issuer, jwks);
    }
}
