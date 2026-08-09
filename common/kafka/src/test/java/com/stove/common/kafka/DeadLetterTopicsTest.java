package com.stove.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.event.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DLT 이름 규칙.
 *
 * <p>규칙이 한 곳에만 있어야 하는 이유가 있다 — 보내는 쪽과 되돌리는 쪽이 갈리면
 * <b>재투입이 "그런 토픽이 없다"로 조용히 실패한다.</b> 실제로 원격에서 그 직전까지 갔다:
 * 스프링 기본 접미사({@code -dlt})가 쓰여 문서·알람이 가리키는 이름과 어긋나 있었다.
 */
class DeadLetterTopicsTest {

    private static ConsumerRecord<String, String> recordOn(String topic, int partition) {
        return new ConsumerRecord<>(topic, partition, 7L, "ORD-1", "{}");
    }

    @Test
    @DisplayName("토픽 명명 규칙을 따른다 — 점으로 끊는다")
    void appendsDottedSuffix() {
        assertThat(DeadLetterTopics.nameFor(Topics.PAYMENT))
                .isEqualTo(Topics.PAYMENT + ".DLT")
                .doesNotContain("-dlt");
    }

    /** 파티션이 바뀌면 같은 애그리거트의 순서가 DLT 안에서 흩어지고, 재투입도 순서를 잃는다. */
    @Test
    @DisplayName("파티션 번호를 유지한다")
    void keepsPartition() {
        assertThat(DeadLetterTopics.of(recordOn(Topics.PAYMENT, 2), new IllegalStateException()))
                .satisfies(target -> {
                    assertThat(target.topic()).isEqualTo(Topics.PAYMENT + ".DLT");
                    assertThat(target.partition()).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("원본 이름을 되돌린다 — 보내는 규칙과 되돌리는 규칙이 짝을 이룬다")
    void reversesToOriginal() {
        assertThat(DeadLetterTopics.originalOf(DeadLetterTopics.nameFor(Topics.PAYMENT)))
                .isEqualTo(Topics.PAYMENT);
    }

    @Test
    @DisplayName("규칙에 맞지 않는 이름은 거절한다 — 원본을 추측하지 않는다")
    void rejectsUnknownName() {
        assertThatThrownBy(() -> DeadLetterTopics.originalOf("stove.payment.v1-dlt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stove.payment.v1-dlt");
    }
}
