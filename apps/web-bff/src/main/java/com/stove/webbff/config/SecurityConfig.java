package com.stove.webbff.config;

import com.stove.webbff.config.bff.BffAuthenticationFilter;
import com.stove.webbff.config.bff.BffTokens;
import com.stove.webbff.config.bff.ExternalAuthorizationRejectFilter;
import com.stove.webbff.config.bff.RedisAuthorizedClients;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.oidc.web.server.logout.OidcClientInitiatedServerLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.csrf.CsrfWebFilter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

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
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                  RedisAuthorizedClients clients, BffTokens tokens,
                                                  ReactiveJwtDecoder decoder,
                                                  ServerOAuth2AuthorizationRequestResolver resolver,
                                                  ReactiveClientRegistrationRepository registrations) {
        OidcClientInitiatedServerLogoutSuccessHandler logoutSuccess = new OidcClientInitiatedServerLogoutSuccessHandler(registrations);
        logoutSuccess.setPostLogoutRedirectUri("{baseUrl}/");
        ServerWebExchangeMatcher csrfMatcher = (ServerWebExchangeMatcher) exchange -> {
            String path = exchange.getRequest().getPath().value();
            boolean delegated = path.equals("/login") || path.equals("/logout") || path.startsWith("/oauth2/")
                    || path.startsWith("/connect/")
                    || path.equals("/api/v1/auth/signup") || path.equals("/api/v1/auth/signup/member")
                    || path.equals("/api/v1/auth/password/reset-request") || path.equals("/api/v1/auth/password/reset")
                    || path.startsWith("/actuator/");
            if (delegated)
                return ServerWebExchangeMatcher.MatchResult.notMatch();
            return CsrfWebFilter.DEFAULT_CSRF_MATCHER.matches(exchange);
        };
        // 결정 220: 외부 Authorization 헤더는 인증·CSRF 판정보다 먼저 401로 끝낸다.
        return http.addFilterAt(new ExternalAuthorizationRejectFilter(), SecurityWebFiltersOrder.FIRST)
                .csrf(csrf -> csrf.requireCsrfProtectionMatcher(csrfMatcher))
                .oauth2Login(login -> login.authorizationRequestResolver(resolver)
                        .authorizedClientRepository(clients)
                        .authenticationFailureHandler((exchange, exception) -> {
                            LoggerFactory.getLogger(SecurityConfig.class).warn("BFF login failed", exception);
                            exchange.getExchange().getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                            return exchange.getExchange().getResponse().setComplete();
                        })
                        .authenticationSuccessHandler(new RedirectServerAuthenticationSuccessHandler("/")))
                .logout(logout -> logout.requiresLogout(ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, "/bff/logout"))
                        .logoutHandler((exchange, authentication) -> clients.key(exchange.getExchange()).flatMap(tokens::revoke))
                        // Spring adds a concurrent CSRF cleanup handler. Invalidate only after all handlers finish.
                        .logoutSuccessHandler((exchange, authentication) -> exchange.getExchange().getSession()
                                .flatMap(WebSession::invalidate)
                                .then(Mono.defer(() -> logoutSuccess.onLogoutSuccess(exchange, authentication)))))
                .addFilterAfter(new BffAuthenticationFilter(clients, tokens, decoder),
                        SecurityWebFiltersOrder.AUTHENTICATION)
                .exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)))
                // 결정 223: 역할별 경로 규칙은 모든 BFF가 공유하는 API 게이트웨이가 정한다. 웹 BFF는 세션이 있으면
                // 서버 쪽 access token을 붙여 넘기고, 없으면 익명으로 넘긴다(보호 경로는 게이트웨이가 401·403).
                // 웹 BFF 자기 경로(/bff/session·/bff/logout)만 여기서 인증을 요구한다.
                .authorizeExchange(authorize -> authorize
                        .pathMatchers("/bff/session", "/bff/logout").authenticated()
                        .pathMatchers("/actuator/health", "/actuator/prometheus", "/swagger-ui/**", "/swagger-ui.html",
                                "/v3/api-docs/**", "/", "/index.html", "/bff/csrf", "/oauth2/authorization/**",
                                "/login/oauth2/code/**", "/oauth2/authorize", "/login", "/logout", "/error",
                                "/.well-known/**", "/connect/**", "/default-ui.css", "/api/**")
                        .permitAll()
                        .anyExchange().denyAll())
                .build();
    }
}
