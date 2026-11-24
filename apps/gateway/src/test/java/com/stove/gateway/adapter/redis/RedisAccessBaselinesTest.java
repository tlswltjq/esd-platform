package com.stove.gateway.adapter.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 거절 기준은 정밀도를 잃더라도 거절 쪽으로만 기운다(PR #246 R4). */
class RedisAccessBaselinesTest {

    @Test
    void subMillisecondChangesAreCeiledNotTruncated() {
        Instant justAfterTheSecond = Instant.parse("2026-11-24T10:00:00.000400Z");

        assertThat(RedisAccessBaselines.ceilMillis(justAfterTheSecond))
                .isEqualTo(Instant.parse("2026-11-24T10:00:00.001Z").toEpochMilli());
        assertThat(RedisAccessBaselines.ceilMillis(Instant.parse("2026-11-24T10:00:00.250Z")))
                .isEqualTo(Instant.parse("2026-11-24T10:00:00.250Z").toEpochMilli());
    }
}
