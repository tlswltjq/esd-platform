package com.stove.gateway.api.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stove.common.contract.EventHeaders;
import com.stove.common.contract.payload.AccountAccessChangedEvent;
import com.stove.common.event.InboundMessage;
import com.stove.gateway.adapter.metrics.MicrometerProcessingLag;
import com.stove.gateway.core.domain.AccessChange;
import com.stove.gateway.core.domain.AccessChangeRejected;
import com.stove.gateway.core.port.AccessBaselines;
import com.stove.gateway.core.service.RecordAccessChange;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;

/** 계정 접근 변경 사실 → 거절 기준(R5.6), 영구·일시 실패의 구분(결정 226), 소비 지연(R5.8). */
class AccountAccessEventListenerTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AccessBaselines baselines = mock(AccessBaselines.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Instant changedAt = Instant.parse("2026-11-24T10:00:00Z");
    private final MicrometerProcessingLag lag = new MicrometerProcessingLag(registry, RecordAccessChange.CONSUMER_GROUP,
            Clock.fixed(changedAt.plusSeconds(30), ZoneOffset.UTC));
    private final AccountAccessEventListener listener =
            new AccountAccessEventListener(mapper, new RecordAccessChange(baselines, lag));

    private InboundMessage message(AccountAccessChangedEvent event, String key) throws Exception {
        return new InboundMessage("stove.auth.v1", key, mapper.writeValueAsString(event),
                Map.of(EventHeaders.EVENT_ID, List.of(event.eventId()), EventHeaders.EVENT_TYPE, List.of(event.eventType())),
                "partition=0 offset=1");
    }

    private double lagSeconds() {
        return registry.get(MicrometerProcessingLag.METRIC).gauge().value();
    }

    @Test
    void raisesTheBaselineOfTheSubject() throws Exception {
        AccountAccessChangedEvent event = AccountAccessChangedEvent.of("usr_1", changedAt, AccountAccessChangedEvent.ROLE);

        listener.receive(message(event, "usr_1"));

        ArgumentCaptor<AccessChange> change = ArgumentCaptor.forClass(AccessChange.class);
        verify(baselines).raise(change.capture());
        assertThat(change.getValue()).isEqualTo(new AccessChange(event.eventId(), "usr_1", changedAt));
        assertThat(lagSeconds()).isZero();
    }

    @Test
    void aKeyThatDisagreesWithTheSubjectIsAPermanentFailure() throws Exception {
        AccountAccessChangedEvent event = AccountAccessChangedEvent.of("usr_1", changedAt, AccountAccessChangedEvent.STATUS);

        // noRetryFor(AccessChangeRejected) → 재시도 없이 DLT(결정 226).
        assertThatThrownBy(() -> listener.receive(message(event, "usr_2"))).isInstanceOf(AccessChangeRejected.class);
        verify(baselines, never()).raise(any());
    }

    @Test
    void aStoreFailureIsRetriedAndTheLagKeepsGrowing() throws Exception {
        AccountAccessChangedEvent event = AccountAccessChangedEvent.of("usr_1", changedAt, AccountAccessChangedEvent.STATUS);
        doThrow(new RedisConnectionFailureException("down")).when(baselines).raise(any());

        // noRetryFor에 없으므로 adapter가 성공할 때까지 다시 넣는다. 그동안 지연은 사실의 나이만큼이다.
        assertThatThrownBy(() -> listener.receive(message(event, "usr_1")))
                .isInstanceOf(RedisConnectionFailureException.class);
        assertThat(lagSeconds()).isEqualTo(30.0);
    }

    @Test
    void aStoreTimeoutIsNotMistakenForAPermanentFailure() {
        // PR #246 R1: block(timeout)은 IllegalStateException을 던진다. 처리 단계의 예외는 DLT가 아니라 성공까지 재시도다.
        AccountAccessChangedEvent event = AccountAccessChangedEvent.of("usr_1", changedAt, AccountAccessChangedEvent.ROLE);
        doThrow(new IllegalStateException("Timeout on blocking read for 3000000000 NANOSECONDS")).when(baselines).raise(any());

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
        InboundMessage registered = new InboundMessage("stove.auth.v1", "usr_1", "{}",
                Map.of(EventHeaders.EVENT_ID, List.of("evt-1"), EventHeaders.EVENT_TYPE, List.of("UserRegistered")), "");

        listener.receive(registered);

        verify(baselines, never()).raise(any());
    }
}
