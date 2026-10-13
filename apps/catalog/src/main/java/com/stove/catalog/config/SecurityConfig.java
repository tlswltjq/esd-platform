package com.stove.catalog.config;

import com.stove.catalog.core.service.AuditLogService;
import com.stove.common.security.ApiJwtSecurity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuditLogService audit) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/metrics/**", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/products/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/products/quote").permitAll()
                        .requestMatchers("/api/v1/promotions/seller/**").hasRole("CREATOR")
                        .requestMatchers("/api/v1/promotions/platform/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/products/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, denied) -> {
                    if (request.getRequestURI().startsWith("/api/v1/promotions/")) {
                        var authentication = org.springframework.security.core.context.SecurityContextHolder
                                .getContext().getAuthentication();
                        String actor = authentication instanceof JwtAuthenticationToken token
                                ? token.getToken().getSubject() : "unknown";
                        audit.recordDenied(actor, request.getRequestURI(), "role-denied");
                    }
                    response.sendError(403);
                }))
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
