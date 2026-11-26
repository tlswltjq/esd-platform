package com.stove.bench.messaging.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LoadPlanTest {

    @Test
    void 인자_기본값은_결정_230의_층_2_기준_조건이다() {
        LoadOptions options = LoadOptions.parse(new String[] {
                "--bootstrap=localhost:9092", "--run-dir=/x", "--rate=100", "--seconds=10", "--warmup=2",
                "--producer.linger.ms=5"});

        assertThat(options.payloadBytes()).isEqualTo(1024);
        assertThat(options.keys()).isEqualTo(600);
        assertThat(options.topic()).isEqualTo("bench.messages.v1");
        assertThat(options.warmupMessages()).isEqualTo(200);
        assertThat(options.measuredMessages()).isEqualTo(1000);
        assertThat(options.producerOverrides()).containsEntry("linger.ms", "5");
        assertThat(LoadGenerator.producerConfig(options))
                .containsEntry("acks", "all").containsEntry("enable.idempotence", "true").containsEntry("retries", "3")
                .containsEntry("linger.ms", "5");
    }

    @Test
    void 모르는_인자와_재시도_횟수를_넘는_일시_실패는_거절한다() {
        assertThatThrownBy(() -> LoadOptions.parse(new String[] {
                "--bootstrap=b", "--run-dir=/x", "--rate=1", "--seconds=1", "--burst=3"}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LoadOptions.parse(new String[] {
                "--bootstrap=b", "--run-dir=/x", "--rate=1", "--seconds=1",
                "--transient-ratio=0.1", "--transient-failures=4"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 본문은_정확히_지정한_바이트다() {
        for (int size : new int[] {64, 1024, 10 * 1024, 100 * 1024}) {
            assertThat(MessagePlan.payload(123_456, size).getBytes(StandardCharsets.UTF_8)).hasSize(size);
        }
    }

    @Test
    void 키는_고르게_돌고_실패는_측정_구간에만_같은_씨앗이면_같게_들어간다() {
        LoadOptions options = LoadOptions.parse(new String[] {
                "--bootstrap=b", "--run-dir=/x", "--rate=1000", "--warmup=1", "--seconds=10", "--keys=3",
                "--transient-ratio=0.05", "--transient-failures=2", "--permanent-ratio=0.01"});

        List<MessagePlan.Message> first = plan(options);
        List<MessagePlan.Message> second = plan(options);

        assertThat(first).extracting(MessagePlan.Message::key).startsWith("k-00000", "k-00001", "k-00002", "k-00000");
        assertThat(first.subList(0, 1000)).allSatisfy(message -> {
            assertThat(message.phase()).isEqualTo("warmup");
            assertThat(message.failure()).isEmpty();
        });
        List<String> failures = first.stream().map(MessagePlan.Message::failure).toList();
        assertThat(failures).isEqualTo(second.stream().map(MessagePlan.Message::failure).toList());
        long transients = failures.stream().filter("transient:2"::equals).count();
        long permanents = failures.stream().filter("permanent"::equals).count();
        // 10,000건 중 5%·1% 근처. 정확한 값은 씨앗이 정한다.
        assertThat(transients).isBetween(400L, 600L);
        assertThat(permanents).isBetween(60L, 140L);
    }

    @Test
    void 일정은_앞이_늦어도_밀리지_않는다() {
        AtomicLong now = new AtomicLong(0);
        Pacer pacer = new Pacer(4, 1_000, now::get);

        assertThat(pacer.scheduledNanos(0)).isEqualTo(1_000);
        assertThat(pacer.scheduledNanos(3)).isEqualTo(1_000 + 750_000_000);
        // 이미 예정보다 늦었으면 기다리지 않고 지금을 돌려준다. 다음 예정 시각은 그대로다.
        now.set(2_000_000_000L);
        assertThat(pacer.awaitSlot(1)).isEqualTo(2_000_000_000L);
        assertThat(pacer.scheduledNanos(2)).isEqualTo(1_000 + 500_000_000);
    }

    @Test
    void 달성률은_예정_시각으로_고정한_창_안에_실제로_보낸_수로_센다() {
        // 10초 창에 100 msg/s 예정 1,000건. 예열에서 밀려 모든 메시지가 3초씩 늦게 나가면, 마지막 3초 몫(300건)은 창 밖이다.
        MeasuredWindow window = new MeasuredWindow(5_000_000_000L, 10);
        for (int i = 0; i < 1000; i++) {
            window.sent(5_000_000_000L + i * 10_000_000L + 3_000_000_000L);
        }

        assertThat(window.inWindow()).isEqualTo(700);
        assertThat(window.afterWindow()).isEqualTo(300);
        assertThat(window.achievedRate()).isEqualTo(70.0);
    }

    private static List<MessagePlan.Message> plan(LoadOptions options) {
        MessagePlan plan = new MessagePlan(options);
        List<MessagePlan.Message> messages = new ArrayList<>();
        for (long i = 0; i < options.warmupMessages() + options.measuredMessages(); i++) {
            messages.add(plan.next(i));
        }
        return messages;
    }
}
