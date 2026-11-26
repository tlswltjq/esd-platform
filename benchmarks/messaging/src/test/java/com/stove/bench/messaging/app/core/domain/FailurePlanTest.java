package com.stove.bench.messaging.app.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FailurePlanTest {

    @Test
    void 헤더가_없으면_실패하지_않는다() {
        assertThat(FailurePlan.parse(null)).isEqualTo(FailurePlan.NONE);
        assertThat(FailurePlan.parse("")).isEqualTo(FailurePlan.NONE);
        assertThat(FailurePlan.NONE.failsOn(1)).isFalse();
    }

    @Test
    void 일시_실패는_앞의_N번만_실패한다() {
        FailurePlan plan = FailurePlan.parse("transient:2");
        assertThat(plan.failsOn(1)).isTrue();
        assertThat(plan.failsOn(2)).isTrue();
        assertThat(plan.failsOn(3)).isFalse();
    }

    @Test
    void 영구_실패는_모든_시도가_실패한다() {
        FailurePlan plan = FailurePlan.parse("permanent");
        assertThat(plan.failsOn(1)).isTrue();
        assertThat(plan.failsOn(4)).isTrue();
        assertThat(plan.failsOn(100)).isTrue();
    }

    @Test
    void 재시도_횟수를_넘는_일시_실패와_모르는_형식은_거절한다() {
        // 총 4회 시도이므로 transient:4 는 일시 실패가 아니라 격리가 된다.
        assertThatThrownBy(() -> FailurePlan.parse("transient:4")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FailurePlan.parse("transient:0x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FailurePlan.parse("sometimes")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FailurePlan.NONE.failsOn(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
