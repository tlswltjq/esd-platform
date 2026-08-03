package com.stove.common.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 릴레이 지표. <b>배경 스레드라 지표가 없으면 관측 수단이 아예 없다.</b>
 *
 * <p>지표 코드는 조용히 망가지는 부류다 — 카운터가 안 올라가도 기능은 정상 동작하고
 * 테스트도 통과한다. 드러나는 시점은 사고가 나서 대시보드를 열었을 때이고,
 * 그때는 이미 <b>과거 데이터가 없다.</b>
 *
 * <p>실제로 뮤테이션 테스트가 이 공백을 짚었다. {@code OutboxMetrics} 의 모든 계측 호출을
 * 지워도 살아남는 뮤턴트가 6건이었다 — 아무도 지표를 보고 있지 않았다는 뜻이다.
 */
class OutboxMetricsTest {

    private MeterRegistry registry;
    private OutboxEventRepository repository;
    private OutboxMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        repository = mock(OutboxEventRepository.class);
        when(repository.countByStatus(any())).thenReturn(0L);
        metrics = new OutboxMetrics(registry, repository);
    }

    private static OutboxEvent event() {
        return OutboxEvent.pending("EVT-1", "Payment", "ORD-1",
                EventType.PAYMENT_COMPLETED, Topics.PAYMENT, "ORD-1", "{}");
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    @Test
    @DisplayName("발행 성공이 published 카운터로 잡힌다")
    void publishedIsCounted() {
        metrics.recordPublished();
        metrics.recordPublished();

        assertThat(counter("stove.outbox.published")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("재시도 대기로 돌아간 실패는 failed 만 올린다 — dead 는 아직 아니다")
    void retryableFailureCountsAsFailedOnly() {
        OutboxEvent event = event();
        event.markFailed("broker down", 10);

        metrics.recordFailed(event);

        assertThat(counter("stove.outbox.failed")).isEqualTo(1.0);
        assertThat(counter("stove.outbox.dead"))
                .as("아직 재시도가 남아 있다")
                .isZero();
    }

    @Test
    @DisplayName("재시도를 소진한 실패는 failed 와 dead 를 함께 올린다 — 둘의 운영 의미가 다르다")
    void exhaustedFailureCountsAsDeadToo() {
        OutboxEvent event = event();
        for (int attempt = 0; attempt < 3; attempt++) {
            event.markFailed("broker down", 3);
        }
        assertThat(event.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.DEAD);

        metrics.recordFailed(event);

        assertThat(counter("stove.outbox.failed")).isEqualTo(1.0);
        assertThat(counter("stove.outbox.dead"))
                .as("DEAD 는 알람 대상이라 별도로 세야 한다")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("릴레이 1회의 소요시간과 처리 건수가 함께 기록된다")
    void relayDurationAndBatchSizeAreRecorded() {
        metrics.recordRelay(TimeUnit.MILLISECONDS.toNanos(250), 200);

        assertThat(registry.get("stove.outbox.relay").timer().count()).isEqualTo(1);
        assertThat(registry.get("stove.outbox.relay").timer().totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(250.0);
        assertThat(registry.get("stove.outbox.batch.size").summary().totalAmount()).isEqualTo(200.0);
    }

    @Test
    @DisplayName("적체량 게이지는 스크레이프 시점에 PENDING 을 센다")
    void pendingGaugeReadsCurrentBacklog() {
        when(repository.countByStatus(OutboxEvent.OutboxStatus.PENDING)).thenReturn(42L);

        // 게이지는 값을 저장하지 않고 읽을 때마다 세므로, 등록 뒤에 바뀐 값도 반영된다.
        assertThat(registry.get("stove.outbox.pending").gauge().value()).isEqualTo(42.0);

        when(repository.countByStatus(OutboxEvent.OutboxStatus.PENDING)).thenReturn(0L);

        assertThat(registry.get("stove.outbox.pending").gauge().value())
                .as("적체가 해소되면 게이지도 내려가야 한다")
                .isZero();
    }
}
