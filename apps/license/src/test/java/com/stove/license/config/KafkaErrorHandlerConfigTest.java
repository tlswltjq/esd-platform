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
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.DomainEvent;
import com.stove.common.event.Topics;
import com.stove.common.event.kafka.EventHeaders;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.event.payload.PaymentCancelledEvent;
import com.stove.common.event.payload.PaymentCompletedEvent;
import com.stove.common.kafka.DeadLetterMetrics;
import com.stove.license.core.service.LicenseService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.transaction.CannotCreateTransactionException;
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

    /** DLT 로 보낸 레코드. 브로커를 세우지 않고 <b>어느 경로가 여기 담기는가</b>만 본다. */
    private final List<ConsumerRecord<?, ?>> deadLettered = new ArrayList<>();

    private final ConsumerRecordRecoverer recoverer = KafkaErrorHandlerConfig.recoverer(
            licenseService, objectMapper, (record, exception) -> deadLettered.add(record));

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

    /**
     * <b>이 테스트는 한 번 틀렸다.</b> 예전에는 방아쇠가
     * {@code DataAccessResourceFailureException("connection pool exhausted")} 였다 —
     * 즉 "커넥션풀이 마르면 환불한다"를 회귀 방어선으로 고정하고 있었다.
     * D-002 가 고친 것은 <b>언제</b> 보상하는가(재시도 뒤에)였고, <b>무엇을</b> 보고 보상하는가는
     * 그대로였다. 그래서 D-027 의 실측에서 60초 DB 장애에 정상 결제 8건이 환불됐다.
     *
     * <p>지금 방아쇠는 저장소와 무관한 실패다. 저장소 장애는 아래 {@code storageFailure*} 가 맡는다.
     */
    @Test
    @DisplayName("[D-002] 재시도가 소진되면 그때 보상 이벤트를 요청한다")
    void recoversByRequestingCompensation() {
        ConsumerRecord<String, String> record = recordOf(
                PaymentCompletedEvent.of(1L, "ORD-1", 42L, 30_000L, "CARD", LINES));

        recoverer.accept(record, new IllegalStateException("지급 규칙 위반"));

        verify(licenseService).recordIssueFailure(eq("ORD-1"), eq(42L), anyString());
    }

    @Test
    @DisplayName("보상 사유에 근본 원인이 드러난다 — 결제 서비스 로그에서 추적할 수 있어야 한다")
    void reasonCarriesRootCause() {
        ConsumerRecord<String, String> record = recordOf(
                PaymentCompletedEvent.of(1L, "ORD-1", 42L, 30_000L, "CARD", LINES));

        recoverer.accept(record, new IllegalStateException("listener failed",
                new IllegalArgumentException("productId 가 없다")));

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(licenseService).recordIssueFailure(anyString(), anyLong(), reason.capture());
        assertThat(reason.getValue())
                .contains("IllegalArgumentException")
                .contains("productId 가 없다");
    }

    @Test
    @DisplayName("[D-027] 저장소 장애는 '지급 불가'가 아니라 '판단 불가'다 — 환불하지 않고 보류한다")
    void storageFailureIsParkedNotCompensated() {
        ConsumerRecord<String, String> record = recordOf(
                PaymentCompletedEvent.of(1L, "ORD-1", 42L, 30_000L, "CARD", LINES));

        recoverer.accept(record, new DataAccessResourceFailureException("connection pool exhausted"));

        verify(licenseService, never()).recordIssueFailure(anyString(), anyLong(), any());
        assertThat(deadLettered).as("보류함에 들어가야 사람이 볼 수 있다").containsExactly(record);
    }

    /**
     * 실측에서 DB 를 끊었을 때 실제로 나온 예외가 이쪽이었다 —
     * {@code CannotCreateTransactionException: Could not open JPA EntityManager for transaction}.
     * {@code DataAccessException} 이 아니라서 그것만 보면 <b>정확히 재려던 장애를 놓친다.</b>
     */
    @Test
    @DisplayName("[D-027] 트랜잭션을 열지도 못한 실패도 저장소 장애다 — DataAccessException 이 아니다")
    void transactionFailureIsStorageFailure() {
        ConsumerRecord<String, String> record = recordOf(
                PaymentCompletedEvent.of(1L, "ORD-1", 42L, 30_000L, "CARD", LINES));

        recoverer.accept(record, new CannotCreateTransactionException("Could not open JPA EntityManager"));

        verify(licenseService, never()).recordIssueFailure(anyString(), anyLong(), any());
        assertThat(deadLettered).containsExactly(record);
    }

    /**
     * 리스너의 예외는 컨테이너를 거치며 {@code ListenerExecutionFailedException} 으로 한 번,
     * 스프링 변환기를 거치며 한 번 더 감싸인다. <b>맨 바깥만 보면 거의 항상 놓친다.</b>
     */
    @Test
    @DisplayName("[D-027] 감싸인 예외 안쪽의 SQLException 도 찾아낸다")
    void nestedSqlExceptionIsStorageFailure() {
        ConsumerRecord<String, String> record = recordOf(
                PaymentCompletedEvent.of(1L, "ORD-1", 42L, 30_000L, "CARD", LINES));

        recoverer.accept(record, new IllegalStateException("listener failed",
                new RuntimeException("wrapped", new SQLException("Access denied for user"))));

        verify(licenseService, never()).recordIssueFailure(anyString(), anyLong(), any());
        assertThat(deadLettered).containsExactly(record);
    }

    /**
     * 지급이 커밋된 뒤 오프셋 커밋 전에 컨슈머가 실패하면 같은 레코드가 다시 온다.
     * 그 재처리가 실패했을 때 결과를 보지 않으면 <b>이미 물건을 받은 주문을 환불한다.</b>
     * 실측 로그에 그 순간이 남아 있다(D-028).
     */
    @Test
    @DisplayName("[D-028] 이미 지급된 주문은 보상하지 않는다 — '지급 실패' 라는 보고가 사실이 아니다")
    void alreadyIssuedOrderIsNotCompensated() {
        when(licenseService.isIssued("ORD-1")).thenReturn(true);
        ConsumerRecord<String, String> record = recordOf(
                PaymentCompletedEvent.of(1L, "ORD-1", 42L, 30_000L, "CARD", LINES));

        recoverer.accept(record, new IllegalStateException("지급 규칙 위반"));

        verify(licenseService, never()).recordIssueFailure(anyString(), anyLong(), any());
        assertThat(deadLettered).containsExactly(record);
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

    /**
     * 다른 서비스는 재시도가 소진되면 무조건 DLT 로 보낸다. 여기만 예외다.
     *
     * <p>보상이 이미 최종 처리이기 때문이다 — 이 시점에 자동 환불이 걸린다.
     * 그런데 리스너가 실패했으므로 Inbox 가드 행은 롤백돼 있어, 재투입하면 멱등 가드가
     * 막아주지 않고 지급이 다시 시도된다. <b>이미 환불된 결제에 라이선스를 발급하게 된다.</b>
     */
    @Test
    @DisplayName("보상으로 종결한 실패는 DLT 로 보내지 않는다 — 재투입되면 환불된 결제에 지급된다")
    void compensatedFailureIsNotDeadLettered() {
        ConsumerRecord<String, String> record = recordOf(
                PaymentCompletedEvent.of(1L, "ORD-1", 42L, 30_000L, "CARD", LINES));

        recoverer.accept(record, new IllegalStateException("지급 규칙 위반"));

        assertThat(deadLettered).as("보상이 걸렸는데 DLT 로도 보냈다").isEmpty();
    }

    @Test
    @DisplayName("보상 대상이 아닌 실패는 DLT 로 보낸다 — 여기서 버리면 그냥 유실이다")
    void nonCompensatedFailureIsDeadLettered() {
        ConsumerRecord<String, String> record = recordOf(
                PaymentCancelledEvent.of(1L, "ORD-1", 42L, 30_000L, "USER_REFUND"));

        recoverer.accept(record, new DataAccessResourceFailureException("connection lost"));

        assertThat(deadLettered).containsExactly(record);
    }

    @Test
    @DisplayName("봉투를 풀 수 없는 레코드도 DLT 로 보낸다 — 무슨 사건인지 모르므로 사람에게 넘긴다")
    void brokenRecordIsDeadLettered() {
        ConsumerRecord<String, String> broken =
                new ConsumerRecord<>(Topics.PAYMENT, 0, 7L, "ORD-1", "{not json");
        broken.headers().add(EventHeaders.EVENT_TYPE,
                com.stove.common.event.EventType.PAYMENT_COMPLETED.getBytes(StandardCharsets.UTF_8));

        recoverer.accept(broken, new IllegalStateException("boom"));

        assertThat(deadLettered).containsExactly(broken);
    }

    /**
     * 브로커가 죽어 있으면 DLT 발행에서도 예외가 난다. 그것이 밖으로 나가면
     * 레코드가 되감겨 무한 재전송이 되므로, 마지막 방어선의 계약이 여기까지 이어져야 한다.
     */
    @Test
    @DisplayName("DLT 발행이 실패해도 예외를 밖으로 내보내지 않는다")
    void deadLetterFailureIsSwallowed() {
        ConsumerRecordRecoverer withBrokenDlt = KafkaErrorHandlerConfig.recoverer(
                licenseService, objectMapper,
                (record, exception) -> {
                    throw new IllegalStateException("broker down");
                });
        ConsumerRecord<String, String> record = recordOf(
                PaymentCancelledEvent.of(1L, "ORD-1", 42L, 30_000L, "USER_REFUND"));

        assertThatCode(() -> withBrokenDlt.accept(record, new IllegalStateException("boom")))
                .doesNotThrowAnyException();
    }

    /**
     * 발행과 계측을 함께 묶은 공용 발행자를 쓰는지 본다.
     *
     * <p>이름을 고친 뒤에도 license 의 DLT 유입만 지표에 안 잡히던 시절이 있었다 —
     * 카운터를 에러 핸들러 쪽에서 올려서, 자기 recoverer 로 직접 보내는 이 경로가 비껴갔다.
     * 지금은 {@code DeadLetterPublisher.to(template, metrics)} 하나로만 만들 수 있다.
     */
    @Test
    @DisplayName("실제 빈은 발행자와 계측을 함께 물고 조립된다")
    void beanIsAssembledWithRealDeadLetterPublisher() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        DeadLetterMetrics metrics = new DeadLetterMetrics(new SimpleMeterRegistry());

        assertThat(config.licenseIssueFailureRecoverer(
                licenseService, objectMapper, kafkaTemplate, metrics)).isNotNull();
    }
}
