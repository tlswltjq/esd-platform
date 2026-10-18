package com.stove.store.config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/storefront/products", "/api/v1/storefront/products/*").permitAll()
                .anyRequest().denyAll()).build();
    }
}
