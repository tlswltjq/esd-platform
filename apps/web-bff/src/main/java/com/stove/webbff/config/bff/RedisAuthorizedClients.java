package com.stove.webbff.config.bff;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.webbff.adapter.redis.RedisSubjectSessions;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
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

/**
 * Tokens are server-side only; Redis keys are scoped to the BFF session.
 *
 * <p>계정 접근 변경(결정 180·225, GW-T18): 토큰 기록을 쓸 때 subject 색인({@link RedisSubjectSessions#SUBJECT_INDEX})에 그 키를
 * 더한다. 사실을 받으면 {@link RedisSubjectSessions}가 색인의 토큰 기록을 모두 지운다. 다음 요청은 토큰 기록이 없어 401이 되고 그 세션도 끝난다
 * ({@code BffAuthenticationFilter}, design 상태 표 19행).
 */
@Component
public class RedisAuthorizedClients implements ServerOAuth2AuthorizedClientRepository {
    private static final Duration TOKEN_LIFETIME = Duration.ofDays(7);
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
                .computeIfAbsent("bff.client-key", ignored -> UUID.randomUUID().toString()));
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
            String index = RedisSubjectSessions.SUBJECT_INDEX + stored.subject();
            return redis.opsForValue().set(key, mapper.writeValueAsString(stored), TOKEN_LIFETIME)
                    .then(redis.opsForSet().add(index, key))
                    .then(redis.expire(index, TOKEN_LIFETIME)).then();
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
            OAuth2AccessToken access = client.getAccessToken();
            OAuth2RefreshToken refresh = client.getRefreshToken();
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
