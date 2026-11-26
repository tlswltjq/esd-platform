package com.stove.common.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.util.backoff.FixedBackOff;

/** 재시도·격리 계측(결정 229)이 오류 처리기의 실제 흐름을 세고, 처리 결과는 바꾸지 않는다. */
class ConsumerRetryMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ConsumerRetryMetrics metrics = new ConsumerRetryMetrics(registry);
    private final ConsumerRecord<String, String> record = new ConsumerRecord<>("fixture.a.v1", 0, 0L, "K", "{}");

    @AfterEach
    void clearGroup() {
        KafkaUtils.clearConsumerGroupId();
    }

    @Test
    void retriesAreCountedAndExhaustionIsCountedAsRecovered() {
        KafkaUtils.setConsumerGroupId("fixture-group");
        List<ConsumerRecord<?, ?>> quarantined = new ArrayList<>();
        // 대기 없이 2회 재시도: 최초 + 2 = 3번 실패한 뒤 격리.
        CommonErrorHandler handler = DeadLetterErrorHandlers.create(
                (failed, exception) -> quarantined.add(failed), new FixedBackOff(0L, 2L), metrics);
        IllegalStateException failure = new IllegalStateException("wrapped", new ArithmeticException("root"));

        assertThat(handle(handler, failure)).as("1번째 실패: 다시 받는다").isFalse();
        assertThat(handle(handler, failure)).as("2번째 실패: 다시 받는다").isFalse();
        assertThat(handle(handler, failure)).as("3번째 실패: 격리").isTrue();

        assertThat(quarantined).containsExactly(record);
        assertThat(registry.get(ConsumerRetryMetrics.FAILED_DELIVERIES)
                .tags("topic", "fixture.a.v1", "group", "fixture-group", "exception", "ArithmeticException")
                .counter().count()).isEqualTo(3.0);
        assertThat(registry.get(ConsumerRetryMetrics.RECOVERED).tags("topic", "fixture.a.v1", "group", "fixture-group")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.find(ConsumerRetryMetrics.RECOVERY_FAILURES).counter()).isNull();
    }

    @Test
    void aFailedRecoveryIsCountedAndTheRecordIsNotTreatedAsHandled() {
        KafkaUtils.setConsumerGroupId("fixture-group");
        CommonErrorHandler handler = DeadLetterErrorHandlers.create((failed, exception) -> {
            throw new IllegalStateException("격리 기록 실패");
        }, new FixedBackOff(0L, 0L), metrics);

        // 격리처가 실패하면 오류 처리기는 처리되지 않은 것으로 돌려준다 — 컨테이너가 그 레코드로 되감아 다시 받는다(offset 미전진).
        assertThat(handle(handler, new IllegalStateException("boom"))).isFalse();

        assertThat(registry.get(ConsumerRetryMetrics.RECOVERY_FAILURES).tags("topic", "fixture.a.v1", "group", "fixture-group")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.find(ConsumerRetryMetrics.RECOVERED).counter()).isNull();
    }

    @Test
    void outsideAConsumerThreadTheGroupIsUnknown() {
        metrics.failedDelivery(record, new IllegalStateException("boom"), 1);

        assertThat(registry.get(ConsumerRetryMetrics.FAILED_DELIVERIES).tag("group", "unknown").counter().count())
                .isEqualTo(1.0);
    }

    private boolean handle(CommonErrorHandler handler, Exception failure) {
        return handler.handleOne(failure, record, mock(Consumer.class), mock(MessageListenerContainer.class));
    }
}
