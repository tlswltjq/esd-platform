package com.stove.studio.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReleaseTest {
    @Test
    @DisplayName("릴리스는 출시 직전 smoke test를 통과해야 발행된다")
    void smokeTestGatesPublication() {
        Submission submission = Submission.create(1L, 2L, 1, 10L, 20L, 30L, 40L);
        Instant now = Instant.now();
        Release release = Release.scheduled(submission, ReleaseChannel.TEST,
                ReleaseChangeType.INITIAL, now, "Asia/Seoul", null);

        assertThatThrownBy(() -> release.publish(now)).isInstanceOf(BusinessException.class);

        release.smokePassed(now);
        release.publish(now);

        assertThat(release.getStatus()).isEqualTo(ReleaseStatus.PUBLISHED);
        assertThat(release.getSmokeTestStatus()).isEqualTo(SmokeTestStatus.PASSED);
    }
}
