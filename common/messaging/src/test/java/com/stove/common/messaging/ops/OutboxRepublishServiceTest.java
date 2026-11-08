package com.stove.common.messaging.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.messaging.outbox.OutboxEvent;
import com.stove.common.messaging.outbox.OutboxEvent.OutboxStatus;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcOperations;

/**
 * [결정 129] 원본이 Kafka 에서 사라진 이벤트를 DB 에 남은 원래 Outbox 행으로 다시 내보낸다.
 * 허용 목록 밖의 타입, SENT 가 아닌 행, 없는 행은 상태를 건드리지 않고 거절한다.
 */
class OutboxRepublishServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-08T09:00:00.123456789Z");

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final JdbcOperations jdbc = mock(JdbcOperations.class);
    private final OutboxRepublishService service =
            new OutboxRepublishService(repository, jdbc, Clock.fixed(NOW, ZoneOffset.UTC),
                    OutboxRepublishTargets.forOwner(OutboxRepublishTargets.LICENSE));

    private static OutboxEvent sent(String eventId, String type) {
        OutboxEvent event = OutboxEvent.pending(eventId, "License", "ORD-1", type, Topics.LICENSE, "ORD-1", "{}");
        event.markSent();
        return event;
    }

    @Test
    @DisplayName("허용된 SENT 행을 발행 대기로 돌리고 실행자·시각·사유를 같은 트랜잭션에 남긴다")
    void republishesAnAllowedSentRowAndRecordsWhoWhenWhy() {
        OutboxEvent event = sent("EVT-1", EventType.LICENSE_GRANTED);
        Instant sentAt = event.getSentAt();
        when(repository.lockByEventId("EVT-1")).thenReturn(Optional.of(event));

        OutboxRepublishResponse response = service.republish("EVT-1", "usr_admin", "DLT 보존 기간 경과");

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(response.eventId()).isEqualTo("EVT-1");
        assertThat(response.eventType()).isEqualTo(EventType.LICENSE_GRANTED);
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.republishedBy()).isEqualTo("usr_admin");
        assertThat(response.republishedAt()).as("DB 정밀도(마이크로초)에 맞춘다")
                .isEqualTo(Instant.parse("2026-11-08T09:00:00.123456Z"));
        verify(jdbc).update(anyString(), eq("EVT-1"), eq(EventType.LICENSE_GRANTED), eq(Timestamp.from(sentAt)),
                eq("usr_admin"), eq("DLT 보존 기간 경과"), eq(Timestamp.from(response.republishedAt())));
    }

    @Test
    @DisplayName("허용 목록은 소유 모듈별이다 — Order 는 지급 확인 요청·정지·복구·승인 정정 요청, License 는 그 결과")
    void allowedTypesAreListedPerOwningModule() {
        assertThat(OutboxRepublishTargets.forOwner(OutboxRepublishTargets.ORDER)).containsExactlyInAnyOrder(
                EventType.ORDER_PAID, EventType.LICENSE_SUSPENSION_REQUESTED,
                EventType.LICENSE_RESTORATION_REQUESTED, EventType.LICENSE_APPROVAL_CORRECTION_REQUESTED);
        assertThat(OutboxRepublishTargets.forOwner(OutboxRepublishTargets.LICENSE)).containsExactlyInAnyOrder(
                EventType.LICENSE_GRANTED, EventType.LICENSE_SUSPENDED,
                EventType.LICENSE_RESTORED, EventType.LICENSE_APPROVAL_CORRECTED);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"payment", "ORDER"})
    @DisplayName("소유 모듈을 모르면 조립 단계에서 거절한다")
    void unknownOwnerIsRejected(String owner) {
        assertThatThrownBy(() -> OutboxRepublishTargets.forOwner(owner)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("다른 모듈이 소유한 타입은 그 행이 있어도 400 — Order 는 License 결과 이벤트를 재발행하지 않는다")
    void anotherModulesTypeIsRejected() {
        OutboxRepublishService order = new OutboxRepublishService(repository, jdbc, Clock.fixed(NOW, ZoneOffset.UTC),
                OutboxRepublishTargets.forOwner(OutboxRepublishTargets.ORDER));
        for (String type : OutboxRepublishTargets.forOwner(OutboxRepublishTargets.LICENSE)) {
            OutboxEvent event = sent("EVT-L", type);
            when(repository.lockByEventId("EVT-L")).thenReturn(Optional.of(event));
            assertRejected(() -> order.republish("EVT-L", "usr_admin", "사유"), ErrorCode.INVALID_REQUEST);
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        }
        OutboxEvent own = sent("EVT-O", EventType.LICENSE_RESTORATION_REQUESTED);
        when(repository.lockByEventId("EVT-O")).thenReturn(Optional.of(own));
        assertThat(order.republish("EVT-O", "usr_admin", "사유").status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("없는 행은 404")
    void missingRowIsNotFound() {
        when(repository.lockByEventId("EVT-X")).thenReturn(Optional.empty());

        assertRejected(() -> service.republish("EVT-X", "usr_admin", "사유"), ErrorCode.NOT_FOUND);
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("허용 목록 밖의 타입은 SENT 여도 400, 상태는 그대로")
    void typeOutsideTheListIsRejected() {
        OutboxEvent event = sent("EVT-2", EventType.PURCHASE_REFUNDED);
        when(repository.lockByEventId("EVT-2")).thenReturn(Optional.of(event));

        assertRejected(() -> service.republish("EVT-2", "usr_admin", "사유"), ErrorCode.INVALID_REQUEST);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("SENT 가 아닌 행(PENDING·DEAD)은 404 — DEAD 는 requeue 로 되살린다")
    void notSentIsRejected() {
        OutboxEvent pending = OutboxEvent.pending("EVT-3", "License", "ORD-1", EventType.LICENSE_SUSPENDED,
                Topics.LICENSE, "ORD-1", "{}");
        OutboxEvent dead = OutboxEvent.pending("EVT-4", "License", "ORD-1", EventType.LICENSE_RESTORED,
                Topics.LICENSE, "ORD-1", "{}");
        dead.markFailed("broker down", 1);
        when(repository.lockByEventId("EVT-3")).thenReturn(Optional.of(pending));
        when(repository.lockByEventId("EVT-4")).thenReturn(Optional.of(dead));

        assertRejected(() -> service.republish("EVT-3", "usr_admin", "사유"), ErrorCode.NOT_FOUND);
        assertRejected(() -> service.republish("EVT-4", "usr_admin", "사유"), ErrorCode.NOT_FOUND);
        assertThat(dead.getStatus()).isEqualTo(OutboxStatus.DEAD);
        verifyNoInteractions(jdbc);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("사유가 없으면 행을 읽기 전에 400")
    void reasonIsRequired(String reason) {
        assertRejected(() -> service.republish("EVT-1", "usr_admin", reason), ErrorCode.INVALID_REQUEST);
        verifyNoInteractions(repository, jdbc);
    }

    @Test
    @DisplayName("사유는 500자까지")
    void reasonIsBounded() {
        OutboxEvent event = sent("EVT-1", EventType.LICENSE_APPROVAL_CORRECTED);
        when(repository.lockByEventId("EVT-1")).thenReturn(Optional.of(event));

        assertRejected(() -> service.republish("EVT-1", "usr_admin", "가".repeat(501)), ErrorCode.INVALID_REQUEST);
        assertThat(service.republish("EVT-1", "usr_admin", "가".repeat(500)).status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("실행자를 알 수 없으면 400")
    void operatorIsRequired() {
        assertRejected(() -> service.republish("EVT-1", " ", "사유"), ErrorCode.INVALID_REQUEST);
        verifyNoInteractions(repository, jdbc);
    }

    private static void assertRejected(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(code));
    }
}
