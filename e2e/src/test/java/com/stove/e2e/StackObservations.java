package com.stove.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;

/**
 * 여정이 끝난 뒤 스택에 남은 것을 본다. 여정의 판정이 모두 초록이어도 릴레이가 막혀 있거나 소비자 하나가 재시도를
 * 소진하고 있을 수 있다. 그것은 응답이 아니라 남은 행으로 드러난다.
 *
 * <p>예전에는 앱의 {@code /actuator/metrics}를 읽었는데, 지금 그 경로는 인증 없이 열려 있지 않다. 스택의 DB를 직접
 * 본다 — Outbox·격리 표가 있는 스키마를 찾아 모두 센다.
 */
final class StackObservations {

    private StackObservations() {
    }

    /**
     * 내보낼 이벤트가 남아 있지 않다. 유입이 멈췄으므로 릴레이가 따라잡을 시간만 주면 0이 된다. 0이 되지 않는 것은 느린
     * 것이 아니라 막힌 것이다(브로커 장애, 재시도를 소진한 이벤트, 릴레이 정지).
     */
    static void requireOutboxDrained(LocalStack stack) {
        List<String> schemas = schemasWith(stack, "outbox_event");
        assertThat(schemas).as("Outbox가 있는 스키마").isNotEmpty();
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            Map<String, String> stuck = new LinkedHashMap<>();
            for (String schema : schemas) {
                String unsent = stack.sql("SELECT COUNT(*) FROM " + schema + ".outbox_event WHERE status<>'SENT'");
                if (!unsent.equals("0")) {
                    stuck.put(schema, unsent);
                }
            }
            assertThat(stuck).as("여정 뒤에도 나가지 않은 Outbox 이벤트(스키마=건수)").isEmpty();
        });
    }

    /** 재시도를 소진해 격리된 소비가 없다. 정상 여정에는 격리될 실패 자체가 없어야 한다. */
    static void requireNothingQuarantined(LocalStack stack) {
        List<String> schemas = schemasWith(stack, "consumer_quarantine");
        assertThat(schemas).as("격리 표가 있는 스키마").isNotEmpty();
        Map<String, String> quarantined = new LinkedHashMap<>();
        for (String schema : schemas) {
            String count = stack.sql("SELECT COUNT(*) FROM " + schema + ".consumer_quarantine");
            if (!count.equals("0")) {
                quarantined.put(schema, count);
            }
        }
        assertThat(quarantined).as("여정 중 격리된 소비(스키마=건수)").isEmpty();
    }

    private static List<String> schemasWith(LocalStack stack, String table) {
        String found = stack.sql("SELECT table_schema FROM information_schema.tables WHERE table_name='" + table
                + "' AND table_schema LIKE 'stove\\_%' ORDER BY table_schema");
        return found.isBlank() ? List.of() : List.of(found.split("\\s+"));
    }
}
