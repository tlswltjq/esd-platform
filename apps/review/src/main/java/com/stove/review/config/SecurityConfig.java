package com.stove.review.config;

import com.stove.common.security.ApiJwtSecurity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/metrics/**", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        .requestMatchers("/api/v1/reviews/**").hasAnyRole("REVIEWER", "ADMIN")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(ApiJwtSecurity.rolesConverter())))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                          @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri) {
        return ApiJwtSecurity.decoder(issuer, jwkSetUri);
    }
}
