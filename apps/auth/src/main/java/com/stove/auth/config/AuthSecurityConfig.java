package com.stove.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;

@Configuration
public class AuthSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer =
                OAuth2AuthorizationServerConfigurer.authorizationServer();
        http.securityMatcher(authorizationServer.getEndpointsMatcher())
                .with(authorizationServer, server -> server.oidc(Customizer.withDefaults()))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint("/login"),
                        new org.springframework.security.web.util.matcher.MediaTypeRequestMatcher(
                                org.springframework.http.MediaType.TEXT_HTML)));
        // Swagger UI의 OAuth 승인 화면은 같은 출처의 프레임에서 로그인 폼을 연다.
        // 기본 DENY이면 프레임이 chrome-error://chromewebdata 로 바뀌고, 그 상태에서
        // 제출된 /login 요청이 Gateway CORS에 의해 403이 된다.
        http.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain applicationSecurityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/v1/auth/signup"))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/auth/signup", "/actuator/health", "/actuator/metrics/**",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .formLogin(Customizer.withDefaults())
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler((request, response, denied) -> {
                    // OAuth 로그인 화면을 오래 열어 둔 뒤 제출하면 세션 고정 방지로 CSRF 토큰이
                    // 바뀌어 403이 된다. 로그인 POST만 새 폼으로 복구하고, 다른 API의 CSRF
                    // 거부는 기본처럼 403으로 유지한다.
                    if ("/login".equals(request.getRequestURI()) && denied instanceof CsrfException) {
                        response.sendRedirect("/login");
                        return;
                    }
                    response.sendError(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN);
                }))
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    @DependsOnDatabaseInitialization
    RegisteredClientRepository registeredClientRepository(
            JdbcOperations jdbcOperations,
            @Value("${stove.auth.redirect-uri:http://localhost:3000/callback}") String redirectUri,
            @Value("${stove.auth.swagger-redirect-uris:http://localhost:8080/swagger-ui/oauth2-redirect.html,http://127.0.0.1:18080/swagger-ui/oauth2-redirect.html,http://localhost:18080/swagger-ui/oauth2-redirect.html}")
            List<String> swaggerRedirectUris) {
        JdbcRegisteredClientRepository repository = new JdbcRegisteredClientRepository(jdbcOperations);
        RegisteredClient studioWeb = RegisteredClient.withId("studio-web")
                .clientId("studio-web")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(redirectUri)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope("studio")
                .clientSettings(org.springframework.security.oauth2.server.authorization.settings.ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .refreshTokenTimeToLive(Duration.ofDays(7))
                        .reuseRefreshTokens(false)
                        .build())
                .build();
        saveIfAbsent(repository, studioWeb);

        RegisteredClient.Builder swagger = RegisteredClient.withId("swagger-ui")
                .clientId("swagger-ui")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope("studio")
                .clientSettings(org.springframework.security.oauth2.server.authorization.settings.ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .build());
        swaggerRedirectUris.stream().map(String::trim).filter(value -> !value.isBlank())
                .forEach(swagger::redirectUri);
        saveIfAbsent(repository, swagger.build());
        return repository;
    }

    private void saveIfAbsent(JdbcRegisteredClientRepository repository, RegisteredClient client) {
        if (repository.findByClientId(client.getClientId()) != null) {
            return;
        }
        try {
            repository.save(client);
        } catch (org.springframework.dao.DuplicateKeyException concurrentStartup) {
            if (repository.findByClientId(client.getClientId()) == null) {
                throw concurrentStartup;
            }
        }
    }

    @Bean
    OAuth2AuthorizationService authorizationService(
            JdbcOperations jdbcOperations, RegisteredClientRepository registeredClients) {
        return new JdbcOAuth2AuthorizationService(jdbcOperations, registeredClients);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(
            JdbcOperations jdbcOperations, RegisteredClientRepository registeredClients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcOperations, registeredClients);
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(SigningKeyStore signingKeyStore) {
        RSAKey rsaKey = signingKeyStore.loadOrCreate();
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(
            @Value("${stove.auth.issuer:http://localhost:8091}") String issuer) {
        return AuthorizationServerSettings.builder().issuer(issuer).build();
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer() {
        return context -> {
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                context.getClaims().audience(java.util.List.of("esd-api"));
                context.getClaims().claim("roles", context.getPrincipal().getAuthorities().stream()
                        .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", ""))
                        .filter(role -> !role.startsWith("SCOPE_"))
                        .toList());
            }
        };
    }

}
