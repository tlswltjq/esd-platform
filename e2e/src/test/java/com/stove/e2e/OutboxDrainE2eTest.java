package com.stove.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.e2e.E2eClient.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * 저니가 끝난 뒤 Outbox 적체가 0 으로 수렴하는가.
 *
 * <p>{@code stove.outbox.pending} 은 <b>적체량</b>이다 — 우상향이면 유입이 처리를 앞선다는 뜻이고,
 * Prometheus 알람도 이 값에 걸려 있다({@code infra/prometheus/alerts.yml}: {@code > 500}).
 * 그런데 <b>아무 테스트도 이 값을 단언하지 않았다.</b> 지표가 있고 알람 규칙도 있는데,
 * 그것이 실제로 내려가는지 확인하는 자리가 없었다.
 *
 * <p>여기서 0 을 요구할 수 있는 이유는 부하가 아니라 저니이기 때문이다 — 유입이 멈췄으므로
 * 릴레이가 따라잡을 시간만 주면 반드시 0 이 된다. <b>0 이 되지 않는 것은 느린 것이 아니라
 * 막힌 것이다</b>(브로커 장애, DEAD 로 떨어진 레코드, 릴레이 정지).
 */
@Order(6)
@DisplayName("관측 — Outbox 적체 수렴")
class OutboxDrainE2eTest {

    private static final String METRIC = "/actuator/metrics/stove.outbox.pending";

    @Test
    @Order(1)
    @DisplayName("발행하는 앱 7종의 stove.outbox.pending 이 0 으로 수렴한다")
    void outboxDrainsToZero() {
        Map<String, String> stuck = new LinkedHashMap<>();

        Stove.publishers.forEach((name, app) -> {
            try {
                Await.untilResponse("%s 의 outbox 적체 해소".formatted(name),
                        () -> app.get(METRIC),
                        response -> response.status() == 200 && pending(response) == 0.0);
            } catch (AssertionError timeout) {
                stuck.put(name, lastKnown(app));
            }
        });

        assertThat(stuck)
                .as("유입이 멈춘 뒤에도 남아 있는 적체는 느린 것이 아니라 막힌 것이다 — "
                        + "브로커 · DEAD 레코드 · 릴레이 정지 순으로 본다")
                .isEmpty();
    }

    private static double pending(Response response) {
        return response.body().path("measurements").path(0).path("value").asDouble(-1);
    }

    /**
     * 실패를 사람이 읽을 문장으로 바꾼다. 남은 건수와 <b>지표를 못 읽은 것</b>은 대응이 다르다.
     *
     * <p>이 일곱은 전부 Outbox 를 쓰므로 게이지가 기동 시점에 등록된다. 404 는 "적체가 없다" 가 아니라
     * <b>릴레이 구성이 빠졌다</b>는 뜻이다 — {@link DeadLetterE2eTest} 의 404 와 의미가 정반대다.
     * (순수 컨슈머인 store·download 는 애초에 목록에 없다 — {@link Stove#publishers})
     */
    private static String lastKnown(E2eClient app) {
        Response response = app.get(METRIC);
        return response.status() == 200
                ? "적체 %.0f건이 남았다".formatted(pending(response))
                : "지표를 읽지 못했다 (%s) — 릴레이 구성이 빠졌을 수 있다".formatted(response);
    }
}
