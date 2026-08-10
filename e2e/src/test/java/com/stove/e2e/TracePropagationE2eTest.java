package com.stove.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.stove.e2e.E2eClient.Response;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * Kafka 를 건너는 트레이스가 실제로 이어지는가.
 *
 * <h2>왜 이 자리에 방어선이 없었나</h2>
 *
 * <p>Outbox 가 발행을 다른 스레드로 미루기 때문에 자동 계측만으로는 트레이스가 끊긴다.
 * 그래서 적재 시점의 {@code traceparent} 를 {@code outbox_event} 에 저장했다가 발행 시점에
 * 헤더로 되살린다 — 이 저장소에서 가장 깊은 발견이고 {@code docs/decisions.md} 17번에 적혀 있다.
 *
 * <p><b>그런데 그것을 지키는 테스트가 하나도 없었다.</b> 누가 {@code OutboxRecorder} 의
 * {@code trace_parent} 저장을 지우거나 {@code spring.kafka.template.observation-enabled} 를 켜도
 * 처리 자체는 계속되고 <b>트레이스만 조용히 조각난다.</b> 회귀 방어선이 없는 자랑이었다.
 *
 * <h2>왜 결제 콜백인가</h2>
 *
 * <p>팬아웃이 가장 큰 경로다. {@code PaymentCompleted} 하나가 license·order·settlement 로 갈라지고,
 * license 가 낳은 {@code LicenseIssued} 가 download 까지 간다. 게이트웨이로 들어왔으므로
 * 트레이스 하나가 <b>여섯 서비스</b>에 걸쳐야 한다 — 그리고 그중 넷은 Kafka 를 건너서 붙는다.
 *
 * <p>연결이 끊기면 컨슈머가 <b>새 트레이스를 시작</b>하므로, 이 traceId 로 조회했을 때
 * 남는 것은 gateway 와 payment 둘뿐이다. 그것이 이 판정이 잡는 그림이다.
 */
@Order(5)
@DisplayName("관측 — Kafka 를 건너는 트레이스 연결")
class TracePropagationE2eTest {

    /**
     * 결제 콜백 하나가 닿아야 하는 서비스들.
     *
     * <p>넷이 Kafka 너머다. gateway·payment 는 HTTP 자동 계측만으로도 이어지므로,
     * <b>이 집합에서 그 둘을 뺀 나머지가 17번 결정이 지키는 것</b>이다.
     */
    private static final Set<String> EXPECTED =
            Set.of("gateway", "payment", "order", "license", "settlement", "download");

    @Test
    @Order(1)
    @DisplayName("결제 콜백 하나의 traceId 가 6개 서비스에 걸친다")
    void traceSpansAllFannedOutServices() {
        String traceId = Journey.paymentTraceId();

        // Tempo 는 ingester 에 들어간 뒤에야 조회에 뜬다. 스팬 자체가 Kafka 홉을 건너오는 시간도 있다.
        Await.untilResponse("Tempo 에서 traceId=%s 조회".formatted(traceId),
                () -> Stove.tempo.get("/api/traces/" + traceId),
                r -> r.status() == 200 && servicesIn(r).containsAll(EXPECTED));

        Set<String> services = servicesIn(Stove.tempo.get("/api/traces/" + traceId));
        assertThat(services)
                .as("""
                        Kafka 를 건너는 구간이 끊기면 컨슈머가 새 트레이스를 시작한다 — \
                        그러면 여기 남는 것은 HTTP 로 이어진 gateway·payment 뿐이다. \
                        decisions.md 17번(적재 시점 traceparent 저장 → 발행 시점 복원)과 \
                        spring.kafka.template.observation-enabled 를 먼저 본다.""")
                .containsAll(EXPECTED);
    }

    /**
     * Tempo 의 트레이스 응답에서 {@code service.name} 리소스 속성을 모은다.
     *
     * <p>응답은 OTLP JSON 이라 {@code batches[].resource.attributes[]} 아래에 있고,
     * 속성이 키-값 목록이라 이름으로 골라내야 한다.
     */
    private static Set<String> servicesIn(Response response) {
        Set<String> services = new LinkedHashSet<>();
        for (JsonNode batch : response.body().path("batches")) {
            for (JsonNode attribute : batch.path("resource").path("attributes")) {
                if ("service.name".equals(attribute.path("key").asText())) {
                    services.add(attribute.path("value").path("stringValue").asText());
                }
            }
        }
        return services;
    }

    @Test
    @Order(2)
    @DisplayName("응답 헤더의 X-Correlation-Id 가 실제 트레이스를 가리킨다")
    void correlationHeaderPointsAtTheTrace() {
        // 장애 문의에 이 값을 달라고 말할 수 있으려면, 그 값으로 실제 조회가 돼야 한다.
        // 값이 있는 것과 쓸 수 있는 것은 다르다 — 빈 문자열이나 NOOP traceId 였던 적이 있다.
        String traceId = Journey.paymentTraceId();

        assertThat(traceId).as("W3C traceId 는 16바이트 = 32자 16진수다").hasSize(32);
        assertThat(Stove.tempo.get("/api/traces/" + traceId).status())
                .as("헤더로 돌려준 값으로 실제 조회가 돼야 한다")
                .isEqualTo(200);
    }
}
