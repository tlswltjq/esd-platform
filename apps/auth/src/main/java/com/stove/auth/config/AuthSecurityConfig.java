package com.stove.auth.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.stove.auth.adapter.oauth.RotatingAuthorizationService;
import com.stove.auth.adapter.oauth.RotatingRefreshProvider;
import com.stove.auth.core.domain.UserAccount;
import com.stove.auth.core.domain.UserAccountRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.jackson2.SecurityJackson2Modules;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.jackson2.OAuth2AuthorizationServerJackson2Module;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class AuthSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http, RotatingAuthorizationService authorizations,
            UserAccountRepository accounts, PlatformTransactionManager transactionManager) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer =
                OAuth2AuthorizationServerConfigurer.authorizationServer();
        http.securityMatcher(authorizationServer.getEndpointsMatcher())
                .with(authorizationServer, server -> server.oidc(Customizer.withDefaults())
                        .tokenEndpoint(endpoint -> endpoint.authenticationProviders(providers -> {
                            for (int i = 0; i < providers.size(); i++) {
                                if (providers.get(i) instanceof OAuth2RefreshTokenAuthenticationProvider) {
                                    providers.set(i, new RotatingRefreshProvider(providers.get(i), authorizations,
                                            accounts, rotationTransactions(transactionManager)));
                                }
                            }
                        })))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(
                                MediaType.TEXT_HTML)));
        // Swagger UI의 OAuth 승인 화면은 같은 출처의 프레임에서 로그인 폼을 연다.
        // 기본 DENY이면 프레임이 chrome-error://chromewebdata 로 바뀌고, 그 상태에서
        // 제출된 /login 요청이 Gateway CORS에 의해 403이 된다.
        http.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain applicationSecurityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/v1/auth/signup", "/api/v1/auth/signup/member")
                        .ignoringRequestMatchers(request -> {
                            String header = request.getHeader("Authorization");
                            return header != null && header.startsWith("Bearer ");
                        }))
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(token -> {
                    Jwt decoded = jwtDecoder.decode(token);
                    if (!decoded.getAudience().contains("esd-api")) {
                        throw new BadJwtException("Invalid API audience");
                    }
                    return decoded;
                })))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/auth/signup", "/api/v1/auth/signup/member", "/actuator/health", "/actuator/metrics/**",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error", "/favicon.ico",
                                "/.well-known/appspecific/**").permitAll()
                        .anyRequest().authenticated())
                .formLogin(login -> login.defaultSuccessUrl("/api/v1/auth/session"))
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler((request, response, denied) -> {
                    // OAuth 로그인 화면을 오래 열어 둔 뒤 제출하면 세션 고정 방지로 CSRF 토큰이
                    // 바뀌어 403이 된다. 로그인 POST만 새 폼으로 복구하고, 다른 API의 CSRF
                    // 거부는 기본처럼 403으로 유지한다.
                    if ("/login".equals(request.getRequestURI()) && denied instanceof CsrfException) {
                        response.sendRedirect("/login");
                        return;
                    }
                    response.sendError(HttpServletResponse.SC_FORBIDDEN);
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
            List<String> swaggerRedirectUris,
            @Value("${stove.auth.bff-client-secret}") String bffSecret,
            @Value("${stove.auth.bff-redirect-uri:http://localhost:8080/login/oauth2/code/gateway-bff}") String bffRedirect,
            @Value("${stove.auth.bff-access-token-seconds:900}") long accessTokenSeconds,
            PasswordEncoder passwordEncoder) {
        JdbcRegisteredClientRepository repository = new JdbcRegisteredClientRepository(jdbcOperations);
        RegisteredClient.Builder studioWeb = RegisteredClient.withId("studio-web")
                .clientId("studio-web")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(redirectUri)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope("studio")
                .scope("commerce")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .refreshTokenTimeToLive(Duration.ofDays(7))
                        .reuseRefreshTokens(false)
                        .build());
        saveOrUpgrade(repository, jdbcOperations, studioWeb.build());

        RegisteredClient.Builder swagger = RegisteredClient.withId("swagger-ui")
                .clientId("swagger-ui")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope("studio")
                .scope("commerce")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .build());
        swaggerRedirectUris.stream().map(String::trim).filter(value -> !value.isBlank())
                .forEach(swagger::redirectUri);
        saveOrUpgrade(repository, jdbcOperations, swagger.build());
        RegisteredClient bff = RegisteredClient.withId("gateway-bff")
                .clientId("gateway-bff")
                .clientSecret(passwordEncoder.encode(bffSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(bffRedirect)
                .postLogoutRedirectUri(bffRedirect.substring(0, bffRedirect.indexOf("/login/oauth2/code/")) + "/")
                .scope("openid").scope("profile").scope("studio").scope("commerce")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true).requireAuthorizationConsent(false).build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofSeconds(accessTokenSeconds))
                        .refreshTokenTimeToLive(Duration.ofDays(7)).reuseRefreshTokens(false).build())
                .build();
        RegisteredClient existingBff = repository.findByClientId("gateway-bff");
        repository.save(existingBff == null ? bff : RegisteredClient.from(bff).id(existingBff.getId()).build());
        return repository;
    }

    private void saveOrUpgrade(JdbcRegisteredClientRepository repository, JdbcOperations jdbcOperations,
                               RegisteredClient client) {
        RegisteredClient existing = repository.findByClientId(client.getClientId());
        if (existing != null) {
            repository.save(RegisteredClient.from(client).id(existing.getId()).build());
            return;
        }
        try {
            repository.save(client);
        } catch (DuplicateKeyException concurrentStartup) {
            if (repository.findByClientId(client.getClientId()) == null) {
                throw concurrentStartup;
            }
        }
    }

    @Bean
    RotatingAuthorizationService authorizationService(
            JdbcOperations jdbcOperations, RegisteredClientRepository registeredClients,
            PlatformTransactionManager transactionManager) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModules(SecurityJackson2Modules.getModules(getClass().getClassLoader()));
        mapper.registerModule(new OAuth2AuthorizationServerJackson2Module());
        mapper.addMixIn(Long.class, PersistedValueMixin.class);
        mapper.addMixIn(List.of("value").getClass(), PersistedValueMixin.class);
        mapper.addMixIn(List.of().getClass(), PersistedValueMixin.class);
        JdbcOAuth2AuthorizationService jdbc = new JdbcOAuth2AuthorizationService(jdbcOperations, registeredClients);
        JdbcOAuth2AuthorizationService.OAuth2AuthorizationRowMapper rows = new JdbcOAuth2AuthorizationService.OAuth2AuthorizationRowMapper(registeredClients);
        rows.setObjectMapper(mapper);
        jdbc.setAuthorizationRowMapper(rows);
        JdbcOAuth2AuthorizationService.OAuth2AuthorizationParametersMapper parameters = new JdbcOAuth2AuthorizationService.OAuth2AuthorizationParametersMapper();
        parameters.setObjectMapper(mapper);
        jdbc.setAuthorizationParametersMapper(parameters);
        return new RotatingAuthorizationService(jdbc, jdbcOperations, rotationTransactions(transactionManager));
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
    abstract static class PersistedValueMixin {}

    private static TransactionTemplate rotationTransactions(PlatformTransactionManager manager) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    @Bean
    OAuth2TokenGenerator<?> tokenGenerator(JWKSource<SecurityContext> keys,
            OAuth2TokenCustomizer<JwtEncodingContext> customizer, RotatingAuthorizationService authorizations) {
        JwtGenerator jwt = new JwtGenerator(new NimbusJwtEncoder(keys));
        jwt.setJwtCustomizer(customizer);
        OAuth2RefreshTokenGenerator refresh = new OAuth2RefreshTokenGenerator();
        OAuth2TokenGenerator<OAuth2RefreshToken> cappedRefresh = context -> {
            OAuth2RefreshToken generated = refresh.generate(context);
            if (generated == null) return null;
            Instant expiry = context.getAuthorization() == null ? null
                    : authorizations.expiresAt(context.getAuthorization().getId());
            return expiry == null ? generated : new OAuth2RefreshToken(generated.getTokenValue(),
                    generated.getIssuedAt(), expiry);
        };
        return new DelegatingOAuth2TokenGenerator(jwt, new OAuth2AccessTokenGenerator(), cappedRefresh);
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
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource,
            @Value("${stove.auth.issuer:http://localhost:8091}") String issuer) {
        NimbusJwtDecoder decoder = (NimbusJwtDecoder)
                OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(
            @Value("${stove.auth.issuer:http://localhost:8091}") String issuer) {
        return AuthorizationServerSettings.builder().issuer(issuer).build();
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer(UserAccountRepository accounts) {
        return context -> {
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                context.getClaims().audience(new ArrayList<>(List.of("esd-api")));
                UserAccount account = accounts.findBySubject(context.getPrincipal().getName())
                        .filter(value -> value.enabled())
                        .orElseThrow(() -> new OAuth2AuthenticationException("invalid_grant"));
                context.getClaims().claim("member_id", account.getId());
                context.getClaims().claim("roles", new ArrayList<>(account.getRoles().stream()
                        .map(Enum::name).sorted().toList()));
            }
        };
    }

}
