package com.stove.gateway.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
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

    /**
     * 결정 223·224: 이 게이트웨이는 내부망에만 있고 BFF만 부른다. 인증은 BFF가 릴레이한 Bearer(JWT 서명·issuer·audience·만료)뿐이고,
     * 세션·쿠키·CSRF는 없다(브라우저 입구와 CSRF는 웹 BFF가 맡는다). 역할별 경로 규칙은 모든 BFF가 공유하도록 여기에 둔다.
     */
    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeExchange(authorize -> authorize
                        .pathMatchers("/actuator/health", "/actuator/prometheus", "/v3/api-docs/**").permitAll()
                        .pathMatchers("/api/v1/auth/signup", "/api/v1/auth/signup/member",
                                "/api/v1/auth/password/reset-request", "/api/v1/auth/password/reset").permitAll()
                        .pathMatchers("/api/v1/auth/password", "/api/v1/auth/session").authenticated()
                        .pathMatchers(HttpMethod.POST, "/api/v1/admin/accounts/*/status",
                                "/api/v1/admin/accounts/*/roles/grant", "/api/v1/admin/accounts/*/roles/revoke").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/v1/admin/catalog-release-jobs", "/api/v1/admin/catalog-release-jobs/*").hasRole("ADMIN")
                        .pathMatchers("/api/v1/products/by-code/*/sales", "/api/v1/products/by-code/*/listing").hasRole("CREATOR")
                        .pathMatchers("/api/v1/admin/products/*/sales/restriction", "/api/v1/admin/products/*/downloads/restriction", "/api/v1/admin/products/*/listing/restriction").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/v1/products/**", "/api/v1/storefront/**")
                        .permitAll()
                        // catalog의 내부 가격 재계산 경로는 외부 라우트가 없다. 보안 필터가
                        // 먼저 401을 반환하면 라우트 부재(404)라는 경계가 흐려진다.
                        .pathMatchers(HttpMethod.POST, "/api/v1/products/quote").permitAll()
                        .pathMatchers("/api/v1/reviews/**").hasAnyRole("REVIEWER", "ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/v1/studio/assets/**").permitAll()
                        .pathMatchers("/api/v1/studio/**").hasRole("CREATOR")
                        .pathMatchers("/api/v1/promotions/seller/**", "/api/v1/settlements/me/**")
                        .hasRole("CREATOR")
                        .pathMatchers("/api/v1/promotions/platform/**").hasRole("ADMIN")
                        .pathMatchers("/api/v1/settlements/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/v1/admin/refunds", "/api/v1/admin/refunds/*").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.POST, "/api/v1/admin/refunds/*/decision").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.POST, "/api/v1/admin/refunds/*/approval-stops").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/v1/admin/refunds/*/reviews").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.POST, "/api/v1/admin/refunds/*/reconsiderations/*/decision").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/v1/admin/refund-cancellation-holds", "/api/v1/admin/refund-cancellation-holds/*").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.POST, "/api/v1/admin/refund-cancellation-holds/*/resume").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/v1/admin/approval-reviews", "/api/v1/admin/approval-reviews/*", "/api/v1/admin/approval-reviews/*/investigations").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.POST, "/api/v1/admin/approval-reviews/*/investigations", "/api/v1/admin/approval-reviews/*/closure").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/v1/admin/refund-delays", "/api/v1/admin/refund-delays/*",
                                "/api/v1/admin/refund-delays/*/investigations").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.POST, "/api/v1/admin/refund-delays/*/investigations", "/api/v1/admin/refund-delays/*/closure").hasRole("ADMIN")
                        .pathMatchers("/api/v1/orders/**", "/api/v1/payments/**", "/api/v1/library/**",
                                "/api/v1/storage/**", "/api/v1/downloads/**").hasRole("MEMBER")
                        .anyExchange().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
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
