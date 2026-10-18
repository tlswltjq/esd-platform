package com.stove.settlement.config;

import com.stove.common.security.ApiJwtSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                          @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri) {
        JwtDecoder trusted=ApiJwtSecurity.decoder(issuer,jwkSetUri);
        return token -> {
            Jwt jwt=trusted.decode(token);
            String subject=jwt.getSubject();
            if(subject==null || subject.isBlank() || subject.length()>100) throw new BadJwtException("A valid subject is required");
            return jwt;
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        .requestMatchers(HttpMethod.GET,"/api/v1/settlements/me/sales","/api/v1/settlements/me/months").hasRole("CREATOR")
                        .requestMatchers(HttpMethod.GET,"/api/v1/settlements/sales","/api/v1/settlements/months",
                                "/api/v1/settlements/pg-receipts","/api/v1/settlements/pg-receipts/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST,"/api/v1/settlements/pg-receipts",
                                "/api/v1/settlements/pg-receipts/*/reconcile","/api/v1/settlements/sellers/*/months/*/close").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(ApiJwtSecurity.rolesConverter())))
                .build();
    }
}
