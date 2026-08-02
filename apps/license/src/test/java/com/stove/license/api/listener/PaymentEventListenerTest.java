package com.stove.license.api.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.kafka.EventHeaders;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.event.payload.PaymentCancelledEvent;
import com.stove.common.event.payload.PaymentCompletedEvent;
import com.stove.license.core.service.LicenseService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Saga 참여자의 실패 처리 정책.
 *
 * <p>여기서 검증하는 것은 "예외를 밖으로 내보내는가" 하나다. 사소해 보이지만
 * 스프링 카프카에서 <b>재시도의 유일한 전제조건</b>이다 — 컨테이너는 리스너의 리턴값을
 * 보지 않고, 정상 리턴을 곧 처리 성공으로 간주해 오프셋을 커밋한다.
 * 오프셋이 커밋되면 {@code DefaultErrorHandler} 가 되감을 대상 자체가 사라진다.
 *
 * <p>자세한 메커니즘은 {@code docs/kafka-consumer-retry.md} 참고.
 * 그래서 Kafka 를 띄우지 않고도 재시도 가능 여부를 리스너 단위에서 판정할 수 있다.
 */
class PaymentEventListenerTest {

    /** 운영에서 주입되는 것과 같은 설정의 매퍼 */
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final LicenseService licenseService = mock(LicenseService.class);
    private final PaymentEventListener listener = new PaymentEventListener(licenseService, objectMapper);

    private static final List<OrderLine> LINES =
            List.of(new OrderLine(1L, "게임 A", 1001L, 30_000L, 1));

    private ConsumerRecord<String, String> recordOf(DomainEvent event) {
        try {
            ConsumerRecord<String, String> record = new ConsumerRecord<>(
                    Topics.PAYMENT, 0, 0L, event.partitionKey(), objectMapper.writeValueAsString(event));
            record.headers().add(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8));
            record.headers().add(EventHeaders.EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8));
            return record;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ConsumerRecord<String, String> paymentCompleted() {
        return recordOf(PaymentCompletedEvent.of(1L, "ORD-1", 42L, 30_000L, "CARD", LINES));
    }

    @Test
    @DisplayName("결제 완료 이벤트는 라이선스 지급으로 이어진다")
    void completedEventIssuesLicense() {
        listener.onPaymentEvent(paymentCompleted());

        verify(licenseService).issue(anyString(), eq(EventType.PAYMENT_COMPLETED),
                eq("ORD-1"), eq(42L), eq(LINES));
        verify(licenseService, never()).recordIssueFailure(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("결제 취소 이벤트는 라이선스 회수로 이어진다")
    void cancelledEventRevokesLicense() {
        listener.onPaymentEvent(recordOf(
                PaymentCancelledEvent.of(1L, "ORD-1", 42L, 30_000L, "USER_REFUND")));

        verify(licenseService).revoke(anyString(), eq(EventType.PAYMENT_CANCELLED),
                eq("ORD-1"), eq("USER_REFUND"));
    }

    @Test
    @DisplayName("관심 없는 eventType 은 아무 일도 하지 않는다")
    void unrelatedEventTypeIsIgnored() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(Topics.PAYMENT, 0, 0L, "ORD-1", "{}");
        record.headers().add(EventHeaders.EVENT_ID, "EVT-1".getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, "SomethingElse".getBytes(StandardCharsets.UTF_8));

        listener.onPaymentEvent(record);

        verifyNoInteractions(licenseService);
    }

    @Test
    @DisplayName("회수 경로의 예외는 그대로 전파된다 — 지급 경로와 정책이 다르다")
    void revokeFailurePropagates() {
        doThrow(new DataAccessResourceFailureException("connection lost"))
                .when(licenseService).revoke(anyString(), anyString(), anyString(), anyString());

        // 같은 리스너 안에서 한쪽 분기만 예외를 삼킨다. 이 비대칭이 결함의 실마리였다.
        assertThatThrownBy(() -> listener.onPaymentEvent(recordOf(
                PaymentCancelledEvent.of(1L, "ORD-1", 42L, 30_000L, "USER_REFUND"))))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    @Tag("known-defect")
    @DisplayName("[D-002] 지급 중 일시 장애는 예외로 전파되어 컨테이너 재시도를 유발해야 한다")
    void shouldPropagateTransientFailureForRetry() {
        doThrow(new DataAccessResourceFailureException("connection pool exhausted"))
                .when(licenseService).issue(anyString(), anyString(), anyString(), anyLong(), any());

        // 기대: 예외가 컨테이너까지 올라가 오프셋이 커밋되지 않고 레코드가 재전송된다.
        // 실제: 리스너가 try/catch 로 삼켜 정상 리턴 → 오프셋 커밋 → 재시도 0회.
        assertThatThrownBy(() -> listener.onPaymentEvent(paymentCompleted()))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    @Tag("known-defect")
    @DisplayName("[D-002] 일시 장애 한 번으로 보상 환불이 발동하면 안 된다")
    void shouldNotCompensateOnTransientFailure() {
        doThrow(new DataAccessResourceFailureException("connection pool exhausted"))
                .when(licenseService).issue(anyString(), anyString(), anyString(), anyLong(), any());

        try {
            listener.onPaymentEvent(paymentCompleted());
        } catch (RuntimeException expectedOnceFixed) {
            // 고쳐진 뒤에는 예외가 올라온다. 그때도 이 검증은 유효하다.
        }

        // 기대: 재시도가 전부 소진된 뒤에만 보상한다(= ErrorHandler 의 recoverer 자리).
        // 실제: 첫 실패에서 즉시 보상 → 정상 결제가 환불된다.
        verify(licenseService, never()).recordIssueFailure(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("현재 동작: 지급이 한 번 실패하면 곧바로 보상 이벤트를 발행한다")
    void currentBehaviourCompensatesImmediately() {
        doThrow(new DataAccessResourceFailureException("connection pool exhausted"))
                .when(licenseService).issue(anyString(), anyString(), anyString(), anyLong(), any());

        listener.onPaymentEvent(paymentCompleted());

        // 예외가 밖으로 나오지 않는다는 사실 자체를 고정해 둔다.
        // 이 테스트가 깨지는 날이 곧 D-002 가 수정된 날이다.
        verify(licenseService).recordIssueFailure(eq("ORD-1"), eq(42L), anyString());
        assertThat(true).as("리스너가 예외를 던지지 않고 정상 리턴했다").isTrue();
    }
}
