package com.stove.gateway.config;

import com.stove.gateway.config.bff.BffAuthenticationFilter;
import com.stove.gateway.config.bff.BffTokens;
import com.stove.gateway.config.bff.RedisAuthorizedClients;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.web.server.logout.OidcClientInitiatedServerLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.csrf.CsrfWebFilter;
import org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Flux;
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
            boolean apiBearer = exchange.getRequest().getHeaders().getFirst("Authorization") != null
                    && exchange.getRequest().getHeaders().getFirst("Authorization").startsWith("Bearer ");
            boolean delegated = path.equals("/login") || path.equals("/logout") || path.startsWith("/oauth2/")
                    || path.startsWith("/connect/") || path.equals("/userinfo")
                    || path.equals("/api/v1/auth/signup") || path.equals("/api/v1/auth/signup/member")
                    || path.startsWith("/actuator/");
            if (apiBearer || delegated)
                return ServerWebExchangeMatcher.MatchResult.notMatch();
            return CsrfWebFilter.DEFAULT_CSRF_MATCHER.matches(exchange);
        };
        return http.csrf(csrf -> csrf.requireCsrfProtectionMatcher(csrfMatcher))
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
                .authorizeExchange(authorize -> authorize
                        .pathMatchers("/actuator/health", "/actuator/prometheus", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                        .permitAll()
                        .pathMatchers("/", "/index.html", "/bff/csrf", "/oauth2/authorization/**", "/login/oauth2/code/**", "/api/v1/auth/signup", "/api/v1/auth/signup/member",
                                "/oauth2/**", "/login", "/logout",
                                "/error", "/.well-known/**", "/connect/**", "/userinfo")
                        .permitAll()
                        .pathMatchers("/bff/session", "/bff/logout", "/api/v1/auth/password", "/api/v1/auth/session").authenticated()
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
                        .pathMatchers("/api/v1/orders/**", "/api/v1/payments/**", "/api/v1/library/**",
                                "/api/v1/downloads/**").hasRole("MEMBER")
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
