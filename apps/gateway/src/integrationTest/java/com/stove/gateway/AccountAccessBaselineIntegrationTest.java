package com.stove.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.contract.EventHeaders;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.AccountAccessChangedEvent;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.gateway.adapter.redis.BaselineCheckingJwtDecoder;
import com.stove.gateway.adapter.redis.RedisAccessBaselines;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * GW-T18 API 게이트웨이 쪽(결정 180·225): Auth가 낸 계정 접근 변경 사실을 실제 Kafka로 받아 실제 Redis에 거절 기준을 두고,
 * 그 전에 발급된 토큰을 거절한다(R5.6·5.7). 같은 사실의 재전달과 더 이른 사실은 기준을 낮추지 않는다(멱등·순서 무관).
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=true")
@Import({InfraContainers.Redis.class, InfraContainers.Kafka.class})
class AccountAccessBaselineIntegrationTest {

    @Autowired RedisAccessBaselines baselines;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper mapper;

    private void publish(AccountAccessChangedEvent event) throws Exception {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(Topics.AUTH, event.subject(), mapper.writeValueAsString(event));
        record.headers().add(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get();
    }

    private Instant baseline(String subject) {
        return baselines.baseline(subject).block(Duration.ofSeconds(5));
    }

    @Test
    void aTokenIssuedBeforeTheAccessChangeIsRejectedAndALaterOneIsNot() throws Exception {
        String subject = "usr_" + UUID.randomUUID();
        Instant changedAt = Instant.parse("2026-11-24T10:00:00.250Z");
        AccountAccessChangedEvent change = AccountAccessChangedEvent.of(subject, changedAt, AccountAccessChangedEvent.ROLE);

        publish(change);
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> baseline(subject) != null);
        assertThat(baseline(subject)).isEqualTo(changedAt);

        // 같은 사실의 재전달, 더 이른 사실: 기준은 그대로다. 더 늦은 사실만 올린다.
        publish(change);
        publish(AccountAccessChangedEvent.of(subject, changedAt.minusSeconds(60), AccountAccessChangedEvent.STATUS));
        Instant later = changedAt.plusSeconds(30);
        publish(AccountAccessChangedEvent.of(subject, later, AccountAccessChangedEvent.STATUS));
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> later.equals(baseline(subject)));

        ReactiveJwtDecoder signatureChecked = mock(ReactiveJwtDecoder.class);
        BaselineCheckingJwtDecoder decoder = new BaselineCheckingJwtDecoder(signatureChecked, baselines, Duration.ofSeconds(1));
        when(signatureChecked.decode("old")).thenReturn(Mono.just(token("old", subject, later.minusSeconds(5))));
        when(signatureChecked.decode("new")).thenReturn(Mono.just(token("new", subject, later.plusSeconds(2))));
        StepVerifier.create(decoder.decode("old")).expectError(BadJwtException.class).verify();
        StepVerifier.create(decoder.decode("new")).expectNextCount(1).verifyComplete();
    }

    private static Jwt token(String value, String subject, Instant issuedAt) {
        return Jwt.withTokenValue(value).header("alg", "RS256").subject(subject)
                .issuedAt(issuedAt).expiresAt(issuedAt.plusSeconds(900)).build();
    }
}
