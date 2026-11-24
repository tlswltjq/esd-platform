package com.stove.webbff;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.contract.EventHeaders;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.AccountAccessChangedEvent;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.webbff.adapter.redis.RedisSubjectSessions;
import com.stove.webbff.config.bff.RedisAuthorizedClients;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.session.ReactiveSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * GW-T18 웹 BFF 쪽(결정 180·225): Auth가 낸 계정 접근 변경 사실을 실제 Kafka로 받아 그 subject의 모든 세션 토큰 기록을 실제
 * Redis에서 지우고, 같은 세션의 다음 요청이 401이 되며 세션도 끝나는지 본다(design 상태 표 19행). 다른 subject는 그대로다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "BFF_CLIENT_SECRET=revocation-integration-secret", "spring.kafka.listener.auto-startup=true"})
@Import({InfraContainers.Redis.class, InfraContainers.Kafka.class})
class AccountAccessRevocationIntegrationTest {

    @Autowired WebTestClient http;
    @Autowired @SuppressWarnings("rawtypes") ReactiveSessionRepository sessions;
    @Autowired RedisAuthorizedClients clients;
    @Autowired ReactiveClientRegistrationRepository registrations;
    @Autowired ReactiveStringRedisTemplate redis;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper mapper;

    /** 로그인한 BFF 세션: 보안 문맥과 서버 쪽 토큰 기록(아직 만료 전)을 Redis에 둔다. 세션 ID를 돌려준다. */
    private String loggedInSession(String subject) {
        Session session = (Session) sessions.createSession().block(Duration.ofSeconds(5));
        OidcIdToken idToken = new OidcIdToken("id-" + subject, Instant.now(), Instant.now().plusSeconds(300),
                Map.of("sub", subject, "iss", "http://localhost:8091", "aud", List.of("gateway-bff")));
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_MEMBER"));
        OAuth2AuthenticationToken authentication =
                new OAuth2AuthenticationToken(new DefaultOidcUser(authorities, idToken), authorities, "gateway-bff");
        String clientKey = "gw18-" + UUID.randomUUID();
        session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(authentication));
        session.setAttribute("bff.client-key", clientKey);
        sessions.save(session).block(Duration.ofSeconds(5));
        ClientRegistration registration = registrations.findByRegistrationId("gateway-bff").block(Duration.ofSeconds(5));
        OAuth2AuthorizedClient client = new OAuth2AuthorizedClient(registration, subject,
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-" + subject,
                        Instant.now(), Instant.now().plusSeconds(600), Set.of("openid")),
                new OAuth2RefreshToken("refresh-" + subject, Instant.now()));
        clients.write(tokenKey(clientKey), client).block(Duration.ofSeconds(5));
        return session.getId();
    }

    private static String tokenKey(String clientKey) {
        return "stove:bff:tokens:" + clientKey;
    }

    private Set<String> indexed(String subject) {
        return Set.copyOf(redis.opsForSet().members(RedisSubjectSessions.SUBJECT_INDEX + subject)
                .collectList().block(Duration.ofSeconds(5)));
    }

    private void publish(AccountAccessChangedEvent event) throws Exception {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(Topics.AUTH, event.subject(), mapper.writeValueAsString(event));
        record.headers().add(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get();
    }

    @Test
    void anAccessChangeEndsEverySessionOfThatSubjectOnly() throws Exception {
        String subject = "usr_" + UUID.randomUUID();
        String other = "usr_" + UUID.randomUUID();
        String first = loggedInSession(subject);
        String second = loggedInSession(subject);
        String untouched = loggedInSession(other);
        Set<String> tokenKeys = indexed(subject);
        assertThat(tokenKeys).as("토큰 기록을 쓸 때 subject 색인에 남긴다").hasSize(2);

        publish(AccountAccessChangedEvent.of(subject, Instant.now(), AccountAccessChangedEvent.STATUS));

        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(redis.countExistingKeys(List.copyOf(tokenKeys)).block(Duration.ofSeconds(5))).isZero());
        assertThat(indexed(subject)).isEmpty();
        for (String session : List.of(first, second)) {
            http.get().uri("/bff/session").cookie("SESSION", session).exchange().expectStatus().isUnauthorized();
            assertThat(sessions.findById(session).block(Duration.ofSeconds(5))).as("그 세션도 끝난다").isNull();
        }
        assertThat(indexed(other)).as("다른 subject의 세션은 그대로").hasSize(1);
        assertThat(sessions.findById(untouched).block(Duration.ofSeconds(5))).isNotNull();
    }
}
