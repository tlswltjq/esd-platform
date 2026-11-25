package com.stove.webbff.api.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stove.common.contract.EventHeaders;
import com.stove.common.contract.payload.AccountAccessChangedEvent;
import com.stove.common.event.InboundMessage;
import com.stove.webbff.adapter.metrics.MicrometerProcessingLag;
import com.stove.webbff.core.domain.AccessChangeRejected;
import com.stove.webbff.core.port.SubjectSessions;
import com.stove.webbff.core.service.EndSubjectSessions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

/** 계정 접근 변경 사실 → 그 subject의 세션 토큰 기록 삭제(R5.6), 영구·일시 실패 구분(결정 226), 소비 지연(R5.8). */
class AccountAccessEventListenerTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final SubjectSessions sessions = mock(SubjectSessions.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Instant changedAt = Instant.parse("2026-11-24T10:00:00Z");
    private final MicrometerProcessingLag lag = new MicrometerProcessingLag(registry, EndSubjectSessions.CONSUMER_GROUP,
            Clock.fixed(changedAt.plusSeconds(30), ZoneOffset.UTC));
    private final AccountAccessEventListener listener =
            new AccountAccessEventListener(mapper, new EndSubjectSessions(sessions, lag));

    private InboundMessage message(AccountAccessChangedEvent event, String key) throws Exception {
        return new InboundMessage("stove.auth.v1", key, mapper.writeValueAsString(event),
                Map.of(EventHeaders.EVENT_ID, List.of(event.eventId()), EventHeaders.EVENT_TYPE, List.of(event.eventType())),
                "partition=0 offset=1");
    }

    private double lagSeconds() {
        return registry.get(MicrometerProcessingLag.METRIC).gauge().value();
    }

    @Test
    void endsEverySessionOfTheSubject() throws Exception {
        listener.receive(message(AccountAccessChangedEvent.of("usr_1", changedAt, AccountAccessChangedEvent.STATUS), "usr_1"));

        verify(sessions).endAll("usr_1");
        assertThat(lagSeconds()).isZero();
    }

    @Test
    void aKeyThatDisagreesWithTheSubjectIsAPermanentFailure() {
        AccountAccessChangedEvent event = AccountAccessChangedEvent.of("usr_1", changedAt, AccountAccessChangedEvent.ROLE);

        assertThatThrownBy(() -> listener.receive(message(event, "usr_2"))).isInstanceOf(AccessChangeRejected.class);
        verify(sessions, never()).endAll(anyString());
    }

    @Test
    void aStoreFailureIsRetriedAndTheLagKeepsGrowing() {
        AccountAccessChangedEvent event = AccountAccessChangedEvent.of("usr_1", changedAt, AccountAccessChangedEvent.ROLE);
        doThrow(new RedisConnectionFailureException("down")).when(sessions).endAll("usr_1");

        assertThatThrownBy(() -> listener.receive(message(event, "usr_1")))
                .isInstanceOf(RedisConnectionFailureException.class);
        assertThat(lagSeconds()).isEqualTo(30.0);
    }

    @Test
    void aStoreTimeoutIsNotMistakenForAPermanentFailure() {
        // PR #246 R1: block(timeout)은 IllegalStateException을 던진다. 처리 단계의 예외는 DLT가 아니라 성공까지 재시도다.
        AccountAccessChangedEvent event = AccountAccessChangedEvent.of("usr_1", changedAt, AccountAccessChangedEvent.ROLE);
        doThrow(new IllegalStateException("Timeout on blocking read for 3000000000 NANOSECONDS")).when(sessions).endAll("usr_1");

        assertThatThrownBy(() -> listener.receive(message(event, "usr_1")))
                .isInstanceOf(IllegalStateException.class).isNotInstanceOf(AccessChangeRejected.class);
    }

    @Test
    void aBrokenEnvelopeIsAPermanentFailure() {
        InboundMessage noType = new InboundMessage("stove.auth.v1", "usr_1", "{}",
                Map.of(EventHeaders.EVENT_ID, List.of("evt-1")), "");

        assertThatThrownBy(() -> listener.receive(noType)).isInstanceOf(AccessChangeRejected.class);
    }

    @Test
    void otherFactsOnTheAuthTopicAreIgnored() {
        listener.receive(new InboundMessage("stove.auth.v1", "usr_1", "{}",
                Map.of(EventHeaders.EVENT_ID, List.of("evt-1"), EventHeaders.EVENT_TYPE, List.of("UserRegistered")), ""));

        verify(sessions, never()).endAll(anyString());
    }
}
