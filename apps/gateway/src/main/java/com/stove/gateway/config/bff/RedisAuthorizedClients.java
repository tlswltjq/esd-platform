package com.stove.gateway.config.bff;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Tokens are server-side only; Redis keys are scoped to the BFF session. */
@Component
public class RedisAuthorizedClients implements ServerOAuth2AuthorizedClientRepository {
    private final ReactiveStringRedisTemplate redis;
    private final ReactiveClientRegistrationRepository registrations;
    private final ObjectMapper mapper;
    public RedisAuthorizedClients(ReactiveStringRedisTemplate redis,
            ReactiveClientRegistrationRepository registrations, ObjectMapper mapper) {
        this.redis = redis;
        this.registrations = registrations;
        this.mapper = mapper;
    }
    public Mono<String> key(ServerWebExchange exchange) {
        return exchange.getSession().map(session -> "stove:bff:tokens:" + session.getAttributes()
                .computeIfAbsent("bff.client-key", ignored -> java.util.UUID.randomUUID().toString()));
    }
    public Mono<OAuth2AuthorizedClient> read(String key) {
        return redis.opsForValue().get(key).flatMap(value -> {
            try {
                Stored stored = mapper.readValue(value, Stored.class);
                return registrations.findByRegistrationId("gateway-bff").map(stored::toAuthorizedClient);
            } catch (Exception exception) { return Mono.error(exception); }
        });
    }
    public Mono<Void> write(String key, OAuth2AuthorizedClient client) {
        Stored stored = Stored.from(client);
        try {
            return redis.opsForValue().set(key, mapper.writeValueAsString(stored), Duration.ofDays(7)).then();
        } catch (Exception exception) { return Mono.error(exception); }
    }
    public Mono<Void> delete(String key) { return redis.delete(key).then(); }
    @Override @SuppressWarnings("unchecked")
    public <T extends OAuth2AuthorizedClient> Mono<T> loadAuthorizedClient(String id, Authentication principal,
            ServerWebExchange exchange) { return key(exchange).flatMap(this::read).map(value -> (T) value); }
    @Override public Mono<Void> saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal,
            ServerWebExchange exchange) {
        return exchange.getSession().flatMap(session -> {
            session.start();
            return key(exchange).flatMap(key -> write(key, client));
        });
    }
    @Override public Mono<Void> removeAuthorizedClient(String id, Authentication principal, ServerWebExchange exchange) {
        return key(exchange).flatMap(this::delete);
    }
    private record Stored(String subject, String access, long issued, long expires, Set<String> scopes,
                          String refresh, long refreshIssued) {
        private static Stored from(OAuth2AuthorizedClient client) {
            var access = client.getAccessToken();
            var refresh = client.getRefreshToken();
            return new Stored(client.getPrincipalName(), access.getTokenValue(),
                    access.getIssuedAt().getEpochSecond(), access.getExpiresAt().getEpochSecond(), access.getScopes(),
                    refresh == null ? null : refresh.getTokenValue(), refresh == null ? 0 : refresh.getIssuedAt().getEpochSecond());
        }

        private OAuth2AuthorizedClient toAuthorizedClient(ClientRegistration registration) {
            return new OAuth2AuthorizedClient(registration, subject,
                    new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, access,
                            Instant.ofEpochSecond(issued), Instant.ofEpochSecond(expires), scopes),
                    refresh == null ? null : new OAuth2RefreshToken(refresh, Instant.ofEpochSecond(refreshIssued)));
        }
    }
}
