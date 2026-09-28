package com.stove.gateway.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter;
import reactor.core.publisher.Flux;

@Configuration
public class SecurityConfig {

    @Bean
    ReactiveJwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                                  @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>("aud",
                        audience -> audience != null && audience.contains("esd-api"))));
        return decoder;
    }

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(authorize -> authorize
                        .pathMatchers("/actuator/health", "/actuator/prometheus", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                        .permitAll()
                        .pathMatchers("/p0-lab/**", "/studio/**", "/api/v1/auth/signup", "/api/v1/auth/signup/member",
                                "/oauth2/**", "/login", "/logout",
                                "/error", "/.well-known/**")
                        .permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/v1/products/**", "/api/v1/storefront/**")
                        .permitAll()
                        // catalog의 내부 가격 재계산 경로는 외부 라우트가 없다. 보안 필터가
                        // 먼저 401을 반환하면 라우트 부재(404)라는 경계가 흐려진다.
                        .pathMatchers(HttpMethod.POST, "/api/v1/products/quote").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/v1/payments/callback").permitAll()
                        .pathMatchers("/api/v1/reviews/**").hasAnyRole("REVIEWER", "ADMIN")
                        // 프로젝트 자격증명은 Studio가 해시 조회·범위 검증한다.
                        .pathMatchers("/api/v1/studio/ci/**").permitAll()
                        .pathMatchers("/api/v1/studio/**").hasRole("CREATOR")
                        .pathMatchers("/api/v1/settlements/**").hasRole("ADMIN")
                        .pathMatchers("/api/v1/orders/**", "/api/v1/payments/**", "/api/v1/library/**",
                                "/api/v1/downloads/**").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                // Swagger OAuth 승인 화면이 같은 게이트웨이 출처의 프레임에서 로그인한다.
                // DENY이면 로그인 프레임이 chrome-error://chromewebdata 로 바뀌어
                // 이후 /login POST가 403으로 끝난다.
                .headers(headers -> headers.frameOptions(frame ->
                        frame.mode(XFrameOptionsServerHttpHeadersWriter.Mode.SAMEORIGIN)))
                .build();
    }

    private ReactiveJwtAuthenticationConverter jwtAuthenticationConverter() {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles == null) {
                return Flux.empty();
            }
            return Flux.fromIterable(roles).map(role -> new SimpleGrantedAuthority("ROLE_" + role));
        });
        return converter;
    }
}
