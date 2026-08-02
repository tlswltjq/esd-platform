package com.stove.license.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.DomainEvent;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.util.backoff.BackOffExecution;

/**
 * 재시도 소진 후의 최종 처리(recoverer)와 백오프 정책.
 *
 * <p>리스너가 예외를 밖으로 내보내게 되면서 보상 트리거가 여기로 옮겨왔다.
 * recoverer 는 컨테이너가 재시도를 전부 소진한 뒤에만 호출되므로,
 * "일시 장애로는 보상하지 않는다"는 성질은 이 클래스와 리스너 둘이 함께 보장한다.
 */
class KafkaErrorHandlerConfigTest {

    private static final List<OrderLine> LINES =
            List.of(new OrderLine(1L, "게임 A", 1001L, 30_000L, 1));

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final LicenseService licenseService = mock(LicenseService.class);
    private final KafkaErrorHandlerConfig config = new KafkaErrorHandlerConfig();
    private final ConsumerRecordRecoverer recoverer =
            config.licenseIssueFailureRecoverer(licenseService, objectMapper);

    private ConsumerRecord<String, String> recordOf(DomainEvent event) {
        try {
            ConsumerRecord<String, String> record = new ConsumerRecord<>(
                    Topics.PAYMENT, 0, 7L, event.partitionKey(), objectMapper.writeValueAsString(event));
            record.headers().add(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8));
            record.headers().add(EventHeaders.EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8));
            return record;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("[D-002] 재시도가 소진되면 그때 보상 이벤트를 요청한다")
    void recoversByRequestingCompensation() {
        ConsumerRecord<String, String> record = recordOf(
                PaymentCompletedEvent.of(1L, "ORD-1", 42L, 30_000L, "CARD", LINES));

        recoverer.accept(record, new DataAccessResourceFailureException("connection pool exhausted"));

        verify(licenseService).recordIssueFailure(eq("ORD-1"), eq(42L), anyString());
    }

    @Test
    @DisplayName("보상 사유에 근본 원인이 드러난다 — 결제 서비스 로그에서 추적할 수 있어야 한다")
    void reasonCarriesRootCause() {
        ConsumerRecord<String, String> record = recordOf(
                PaymentCompletedEvent.of(1L, "ORD-1", 42L, 30_000L, "CARD", LINES));

        recoverer.accept(record, new IllegalStateException("listener failed",
                new DataAccessResourceFailureException("connection pool exhausted")));

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(licenseService).recordIssueFailure(anyString(), anyLong(), reason.capture());
        assertThat(reason.getValue())
                .contains("DataAccessResourceFailureException")
                .contains("connection pool exhausted");
    }

    @Test
    @DisplayName("회수 실패는 보상 대상이 아니다 — 결제를 되돌릴 일이 아니다")
    void revokeFailureDoesNotCompensate() {
        ConsumerRecord<String, String> record = recordOf(
                PaymentCancelledEvent.of(1L, "ORD-1", 42L, 30_000L, "USER_REFUND"));

        recoverer.accept(record, new DataAccessResourceFailureException("connection lost"));

        verifyNoInteractions(licenseService);
    }

    @Test
    @DisplayName("보상 처리 자체가 실패해도 예외를 밖으로 내보내지 않는다 — 무한 재전송 방지")
    void recovererSwallowsItsOwnFailure() {
        // 봉투를 풀 수 없는 레코드. recoverer 에서 예외가 나가면 컨테이너가 되감아
        // 같은 레코드를 영원히 다시 준다(파티션 정지).
        ConsumerRecord<String, String> broken =
                new ConsumerRecord<>(Topics.PAYMENT, 0, 7L, "ORD-1", "{not json");
        broken.headers().add(EventHeaders.EVENT_TYPE,
                com.stove.common.event.EventType.PAYMENT_COMPLETED.getBytes(StandardCharsets.UTF_8));

        assertThatCode(() -> recoverer.accept(broken, new IllegalStateException("boom")))
                .doesNotThrowAnyException();
        verify(licenseService, never()).recordIssueFailure(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("[D-002] 재시도 간격이 시도마다 늘어난다 — 기본값이면 전부 0ms 다")
    void backOffGrowsBetweenAttempts() {
        BackOffExecution execution = KafkaErrorHandlerConfig.backOff().start();

        long first = execution.nextBackOff();
        long second = execution.nextBackOff();
        long third = execution.nextBackOff();

        assertThat(first).isEqualTo(1_000L);
        assertThat(second).isGreaterThan(first);
        assertThat(third).isGreaterThan(second);
        assertThat(execution.nextBackOff()).as("소진").isEqualTo(BackOffExecution.STOP);
    }

    @Test
    @DisplayName("총 대기가 max.poll.interval.ms(기본 5분) 안에 들어온다 — 넘으면 리밸런싱된다")
    void totalBackOffStaysWithinPollInterval() {
        BackOffExecution execution = KafkaErrorHandlerConfig.backOff().start();

        long total = 0;
        for (long interval = execution.nextBackOff();
             interval != BackOffExecution.STOP;
             interval = execution.nextBackOff()) {
            total += interval;
        }

        // 블로킹 재시도라 이 시간만큼 해당 파티션이 멈춘다.
        assertThat(total).isPositive().isLessThan(300_000L);
    }

    @Test
    @DisplayName("에러 핸들러는 재시도 정책과 보상 진입점을 함께 물고 있다")
    void errorHandlerIsWired() {
        assertThat(config.kafkaErrorHandler(recoverer)).isNotNull();
    }
}
