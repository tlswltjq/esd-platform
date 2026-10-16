package com.stove.gateway.config.bff;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Component
public class BffTokens {
    private final RedisAuthorizedClients clients;
    private final ReactiveStringRedisTemplate redis;
    private final WebClient http = WebClient.builder().build();
    public BffTokens(RedisAuthorizedClients clients, ReactiveStringRedisTemplate redis) {
        this.clients = clients;
        this.redis = redis;
    }

    public Mono<OAuth2AuthorizedClient> current(String key) {
        return clients.read(key).switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED)))
                .flatMap(client -> fresh(client) ? Mono.just(client) : coordinate(key, Instant.now().plusSeconds(12)));
    }
    private boolean fresh(OAuth2AuthorizedClient client) {
        return client.getAccessToken().getExpiresAt().isAfter(Instant.now().plusSeconds(10));
    }
    private Mono<OAuth2AuthorizedClient> coordinate(String key, Instant deadline) {
        String owner = UUID.randomUUID().toString();
        String lock = key + ":refresh-lock";
        // The HTTP timeout is shorter than the lease; other instances wait for the persisted result.
        return redis.opsForValue().setIfAbsent(lock, owner, Duration.ofSeconds(15)).flatMap(acquired -> {
            if (!acquired) {
                if (Instant.now().isAfter(deadline)) return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
                return Mono.delay(Duration.ofMillis(100)).then(clients.read(key))
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED)))
                        .flatMap(client -> fresh(client) ? Mono.just(client) : coordinate(key, deadline));
            }
            return Mono.usingWhen(Mono.just(owner), ignored -> clients.read(key)
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED)))
                    .flatMap(client -> fresh(client) ? Mono.just(client) : refresh(key, client)),
                    ignored -> unlock(lock, owner), (ignored, error) -> unlock(lock, owner), ignored -> unlock(lock, owner));
        });
    }
    private Mono<Void> unlock(String key, String owner) {
        return redis.execute(new DefaultRedisScript<Long>(
                "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end", Long.class),
                java.util.List.of(key), java.util.List.of(owner)).then();
    }
    private Mono<OAuth2AuthorizedClient> refresh(String key, OAuth2AuthorizedClient client) {
        if (client.getRefreshToken() == null) return clients.delete(key)
                .then(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED)));
        ClientRegistration registration = client.getClientRegistration();
        return http.post().uri(registration.getProviderDetails().getTokenUri())
                .headers(headers -> headers.setBasicAuth(registration.getClientId(), registration.getClientSecret()))
                .body(BodyInserters.fromFormData("grant_type", "refresh_token")
                        .with("refresh_token", client.getRefreshToken().getTokenValue()))
                .exchangeToMono(response -> {
                    if (response.statusCode().is4xxClientError()) return clients.delete(key)
                            .then(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login required")));
                    if (!response.statusCode().is2xxSuccessful()) return clients.delete(key)
                            .then(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login required")));
                    return response.bodyToMono(Map.class);
                }).timeout(Duration.ofSeconds(5))
                // An ambiguous network failure may have consumed the old refresh token.
                // Do not retry it and accidentally trigger replay revocation.
                .onErrorResume(java.util.concurrent.TimeoutException.class, error -> clients.delete(key)
                        .then(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login required"))))
                .onErrorResume(org.springframework.web.reactive.function.client.WebClientRequestException.class,
                        error -> clients.delete(key).then(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login required"))))
                .flatMap(body -> {
                    Instant now = Instant.now();
                    String access = (String) body.get("access_token");
                    String refresh = (String) body.get("refresh_token");
                    if (access == null || refresh == null) return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY));
                    OAuth2AuthorizedClient updated = new OAuth2AuthorizedClient(registration, client.getPrincipalName(),
                            new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, access, now,
                                    now.plusSeconds(((Number) body.get("expires_in")).longValue()), client.getAccessToken().getScopes()),
                            new OAuth2RefreshToken(refresh, now));
                    return clients.write(key, updated).thenReturn(updated);
                });
    }

    public Mono<Void> revoke(String key) {
        return clients.read(key).flatMap(client -> {
            if (client.getRefreshToken() == null) return Mono.empty();
            ClientRegistration registration = client.getClientRegistration();
            String endpoint = registration.getProviderDetails().getTokenUri().replace("/token", "/revoke");
            return http.post().uri(endpoint)
                    .headers(headers -> headers.setBasicAuth(registration.getClientId(), registration.getClientSecret()))
                    .body(BodyInserters.fromFormData("token", client.getRefreshToken().getTokenValue())
                            .with("token_type_hint", "refresh_token"))
                    .retrieve().toBodilessEntity().timeout(Duration.ofSeconds(5)).then();
        }).then(clients.delete(key));
    }
}
