package com.stove.payment.config;

import com.stove.common.security.ApiJwtSecurity;
import com.stove.payment.core.port.PortOneWebhookVerifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

@Configuration
public class SecurityConfig {
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectProvider<PortOneWebhookVerifier> verifiers,
            PortOneProperties portOne) throws Exception {
        PortOneWebhookVerifier verifier = verifiers.getIfAvailable();
        http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(a -> {
            a.requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
            if (verifier != null) a.requestMatchers(HttpMethod.POST, "/api/v1/payments/webhooks/portone").permitAll();
            a.requestMatchers(HttpMethod.GET, "/api/v1/admin/refund-cancellation-holds", "/api/v1/admin/refund-cancellation-holds/*").hasRole("ADMIN");
            a.requestMatchers(HttpMethod.POST, "/api/v1/admin/refund-cancellation-holds/*/resume").hasRole("ADMIN");
            a.requestMatchers(HttpMethod.GET, "/api/v1/admin/refund-delays", "/api/v1/admin/refund-delays/*",
                    "/api/v1/admin/refund-delays/*/investigations").hasRole("ADMIN");
            a.requestMatchers(HttpMethod.POST, "/api/v1/admin/refund-delays/*/investigations").hasRole("ADMIN");
            a.requestMatchers(HttpMethod.GET, "/api/v1/payments", "/api/v1/payments/*", "/api/v1/payments/*/checkout", "/api/v1/payments/*/attempt-failures")
                    .hasRole("MEMBER");
            a.anyRequest().denyAll();
        }).oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(ApiJwtSecurity.rolesConverter())));
        if (verifier != null) http.addFilterBefore(new PortOneWebhookAuthenticationFilter(verifier, portOne.storeId()),
                BearerTokenAuthenticationFilter.class);
        return http.build();
    }
    @Bean JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                               @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwks) {
        return ApiJwtSecurity.decoder(issuer, jwks);
    }
}
