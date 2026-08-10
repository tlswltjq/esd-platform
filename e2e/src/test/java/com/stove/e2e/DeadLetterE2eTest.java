package com.stove.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.e2e.E2eClient.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * 저니를 도는 동안 DLT 로 떨어진 메시지가 있는가.
 *
 * <p>DLT 는 유실을 막지만 <i>알려주지는</i> 않는다. 아무도 보지 않으면 메시지가 조용히 쌓이기만 하고,
 * 그건 운영상 유실과 크게 다르지 않다({@code decisions.md} 19번). 그래서 카운터를 뒀는데 —
 * <b>인수 시나리오는 그 값을 한 번도 보지 않았다.</b> 저니가 초록인 채로 컨슈머 한 종이 계속
 * 재시도를 소진하고 있어도 알 수 없었다.
 *
 * <p>정상 저니에서 기대값은 0 이다. 재시도를 소진할 실패 자체가 없어야 한다 —
 * 3-B 가 만드는 실패는 <b>HTTP 거절</b>이지 컨슈머 예외가 아니다.
 */
@Order(7)
@DisplayName("관측 — DLT 유입")
class DeadLetterE2eTest {

    private static final String METRIC = "/actuator/metrics/stove.kafka.dead-lettered";

    @Test
    @Order(1)
    @DisplayName("앱 9종에서 DLT 로 넘어간 메시지가 없다")
    void nothingWasDeadLettered() {
        Map<String, Double> deadLettered = new LinkedHashMap<>();

        Stove.consumers.forEach((name, app) -> {
            Response response = app.get(METRIC);
            if (response.status() == 200) {
                deadLettered.put(name, total(response));
            } else if (response.status() != 404) {
                throw new AssertionError("%s 의 DLT 지표를 읽지 못했다 — %s".formatted(name, response));
            }
        });

        // **404 를 조용히 0 으로 세지 않는다.**
        //
        // 이 카운터는 첫 유입에서야 등록된다(DeadLetterMetrics 가 recordDeadLettered 안에서 builder 를 부른다).
        // 그래서 404 는 진짜로 "한 번도 없었다" 는 뜻이 맞다 — 다만 그 사실을 화면에 남긴다.
        // 없는 것과 0인 것이 구분되지 않으면, 지표 이름이 바뀌어 전부 404 가 된 날에도 이 판정은 초록이다.
        System.out.printf("  DLT 카운터가 생긴 앱: %s / 아직 없는 앱: %d종%n",
                deadLettered.isEmpty() ? "없음" : deadLettered.keySet(),
                Stove.consumers.size() - deadLettered.size());

        assertThat(deadLettered)
                .as("컨슈머가 재시도를 소진했다는 뜻이다. 어느 흐름인지는 topic 태그가 말해 준다 "
                        + "(/actuator/prometheus 의 stove_kafka_dead_lettered_total)")
                .allSatisfy((app, count) -> assertThat(count).isZero());
    }

    private static double total(Response response) {
        return response.body().path("measurements").path(0).path("value").asDouble(-1);
    }
}
