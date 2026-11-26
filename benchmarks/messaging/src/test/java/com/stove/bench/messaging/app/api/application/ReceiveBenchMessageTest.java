package com.stove.bench.messaging.app.api.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stove.bench.messaging.app.core.domain.Attempt;
import com.stove.bench.messaging.app.core.domain.Attempt.Outcome;
import com.stove.bench.messaging.app.core.domain.BenchInjectedFailure;
import com.stove.bench.messaging.app.core.domain.BenchMessage;
import com.stove.bench.messaging.app.core.domain.FailurePlan;
import com.stove.bench.messaging.app.core.service.RecordBenchMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReceiveBenchMessageTest {

    private final RecordBenchMessage service = mock(RecordBenchMessage.class);
    private final List<Attempt> recorded = new ArrayList<>();
    private final ReceiveBenchMessage receive = new ReceiveBenchMessage(service, recorded::add,
            Clock.fixed(Instant.parse("2026-11-26T00:00:00.000001Z"), ZoneOffset.UTC));

    @Test
    void 실패한_시도는_트랜잭션_밖에서_기록되고_다음_시도의_번호가_오른다() {
        BenchMessage message = message(new FailurePlan(1, false));
        when(service.execute(any(), eq(1))).thenThrow(new BenchInjectedFailure("e-1", 1, message.failure()));
        when(service.execute(any(), eq(2))).thenReturn(true);

        assertThatThrownBy(() -> receive.receive(message, 2, 40L)).isInstanceOf(BenchInjectedFailure.class);
        receive.receive(message, 2, 40L);

        assertThat(recorded).extracting(Attempt::attempt, Attempt::outcome, Attempt::error).containsExactly(
                tuple(1, Outcome.FAILED, "BenchInjectedFailure"),
                tuple(2, Outcome.APPLIED, ""));
        assertThat(recorded.get(0).receivedAtMicros()).isEqualTo(1_795_651_200_000_001L);
        assertThat(recorded.get(0).partition()).isEqualTo(2);
        assertThat(recorded.get(0).offset()).isEqualTo(40L);
    }

    @Test
    void 반영이_끝나면_수를_지워_같은_메시지를_다시_받으면_1부터_센다() {
        BenchMessage message = message(FailurePlan.NONE);
        when(service.execute(any(), eq(1))).thenReturn(true, false);

        receive.receive(message, 0, 1L);
        receive.receive(message, 0, 1L);

        assertThat(recorded).extracting(Attempt::attempt, Attempt::outcome).containsExactly(
                tuple(1, Outcome.APPLIED),
                tuple(1, Outcome.DUPLICATE));
    }

    private static BenchMessage message(FailurePlan failure) {
        return new BenchMessage("e-1", "k-00001", 1L, failure, 1024);
    }
}
