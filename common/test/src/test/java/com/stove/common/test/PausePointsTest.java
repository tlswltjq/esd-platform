package com.stove.common.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.test.PausePoints.Gate;
import com.stove.common.test.PausePoints.Run;
import com.stove.common.test.PausePoints.Watch;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PausePointsTest {
    static final String POINT = "Repository.lock";

    @Test
    @DisplayName("PR #118 리뷰: 테스트가 풀기 전에 실패해도 reset이 세운 스레드를 바로 풀어 잠금을 쥔 채 남기지 않는다")
    void resetReleasesAThreadThatAlreadyArrived() throws Exception {
        PausePoints points = new PausePoints();
        Gate gate = points.arm(POINT, "holder");
        Run<String> holder = PausePoints.start("holder", () -> { points.passed(POINT); return "done"; });
        gate.awaitArrival();

        long started = System.nanoTime();
        List<String> left = points.reset();

        assertThat(left).isEmpty();
        assertThat(holder.join()).isEqualTo("done");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("지켜보는 스레드가 들어가기 전에는 기다림으로 보지 않고, 들어간 뒤 나오지 못할 때만 기다림으로 본다")
    void watchSeparatesALateStartFromWaitingInsideTheCall() throws Exception {
        PausePoints points = new PausePoints();
        Watch quick = points.watch(POINT, "quick");
        PausePoints.start("quick", () -> { points.entered(POINT); points.returned(POINT); return null; }).join();
        assertThat(quick.stillInsideAfter(Duration.ofMillis(200))).isFalse();

        Watch stuck = points.watch(POINT, "stuck");
        PausePoints.start("stuck", () -> { points.entered(POINT); Thread.sleep(3000); points.returned(POINT); return null; });
        assertThat(stuck.stillInsideAfter(Duration.ofMillis(500))).isTrue();
        points.reset();
    }
}
