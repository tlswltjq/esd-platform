package com.stove.bench.messaging.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 손으로 만든 회차 원본으로 요약 계산을 확인한다. 메시지(시각 단위 µs, 요청 시각 = 1초 × 번호):
 * m1 키 A 한 번에 반영(뒤에 재전달 DUPLICATE) · m2 키 A 일시 실패 1회 뒤 반영 · m3 키 B 영구 실패로 격리 · m4 키 B 키 정지로 보관 ·
 * m5 키 C 확인됐는데 최종 상태 없음(유실) · m6·m7 키 D 커밋 순서가 발행 순서와 뒤집힘, m6은 시험 표에 두 행(중복 반영) ·
 * m8 확인 실패 · w1 예열.
 */
class RunReportTest {

    @TempDir
    Path runDir;

    @BeforeEach
    void writeRun() throws IOException {
        write("published.csv", """
                event_id,key,phase,fail,payload_bytes,scheduled_us,requested_us,ack_ns,partition,offset,error
                w1,k-A,warmup,,64,0,0,1000000,0,0,
                m1,k-A,measure,,64,1000000,1000000,2000000,0,1,
                m2,k-A,measure,transient:1,64,2000000,2000000,2000000,0,2,
                m3,k-B,measure,permanent,64,3000000,3000000,2000000,1,0,
                m4,k-B,measure,,64,4000000,4000000,2000000,1,1,
                m5,k-C,measure,,64,5000000,5000000,2000000,2,0,
                m6,k-D,measure,,64,6000000,6000000,2000000,2,1,
                m7,k-D,measure,,64,7000000,7000000,2000000,2,2,
                m8,k-E,measure,,64,8000000,8000000,9000000,-1,-1,TimeoutException
                """);
        write("attempts-1-100.csv", """
                event_id,attempt,partition,offset,received_us,finished_us,outcome,error,end
                w1,1,0,0,1010000,1020000,APPLIED,,#
                m1,1,0,1,1010000,1030000,APPLIED,,#
                m2,1,0,2,2010000,2020000,FAILED,BenchInjectedFailure,#
                m2,2,0,2,3020000,3030000,APPLIED,,#
                m3,1,1,0,3010000,3011000,FAILED,BenchInjectedFailure,#
                m3,2,1,0,4011000,4012000,FAILED,BenchInjectedFailure,#
                m3,3,1,0,6012000,6013000,FAILED,BenchInjectedFailure,#
                m3,4,1,0,10013000,10014000,FAILED,BenchInjectedFailure,#
                m6,1,2,1,7010000,7020000,APPLIED,,#
                m7,1,2,2,7010000,7015000,APPLIED,,#
                """);
        // 재시작한 프로세스의 기록 파일: m1 재전달을 Inbox가 걸렀다. 쓰기 실패 뒤 다시 쓴 흔적(끊긴 줄, 같은 줄 두 번)도 있다.
        write("attempts-2-200.csv", """
                event_id,attempt,partition,offset,received_us,finished_us,outcome,error,end
                m1,1,0,1,9000000,9001000,DUPLICATE,,#
                m1,1,0,1,90
                m3,1,1,0,3010000,3011000,FAILED,BenchInj
                m1,1,0,1,9000000,9001000,DUPLICATE,,#
                """);
        write("bench_message.tsv", String.join("\n",
                "1\tw1\tk-A\t1\t1019000", "2\tm1\tk-A\t1\t1029000", "4\tm2\tk-A\t2\t3029000",
                "5\tm7\tk-D\t1\t7014000", "6\tm6\tk-D\t1\t7019000", "7\tm6\tk-D\t1\t9500000", ""));
        write("quarantine.tsv", "m3\tHELD\t0\t10015000\nm4\tHELD\t1\t10016000\n");
        write("generator.json", "{\"measured\":{\"target_rate\":1.0,\"achieved_rate\":0.5,"
                + "\"window_start_us\":1000000,\"window_end_us\":9000000}}");
        write("run.json", "{\"recovery\":\"quarantine\",\"kills_us\":[8500000]}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 정합성은_예열_포함_모든_메시지로_판정한다() {
        Map<String, Object> summary = RunReport.summarize(RunInputs.read(runDir));
        Map<String, Object> integrity = (Map<String, Object>) summary.get("integrity");

        assertThat(integrity).containsEntry("lost", 1L).containsEntry("lost_sample", List.of("m5"))
                .containsEntry("duplicate_applied", 1L)
                .containsEntry("duplicate_delivered_messages", 1L).containsEntry("duplicate_deliveries", 1L)
                .containsEntry("order_violations", 1L).containsEntry("keys_with_order_violation", 1L)
                .containsEntry("expected_final_failures", 1L).containsEntry("injected_but_completed", 0L)
                .containsEntry("unexpected_final_failures", 0L).containsEntry("held_behind_stopped_key", 1L)
                .containsEntry("unacked_but_delivered", 0L);
        Map<String, Object> counts = (Map<String, Object>) summary.get("counts");
        assertThat(counts).containsEntry("requested", 9).containsEntry("requested_measure", 8)
                .containsEntry("acked", 8L).containsEntry("ack_failed", 1L)
                .containsEntry("completed", 5).containsEntry("failed_attempts", 5L)
                .containsEntry("quarantined_failed", 1L).containsEntry("quarantined_key_stopped", 1L)
                .containsEntry("completed_without_attempt_record", 0L)
                .containsEntry("attempt_log_malformed_lines", 2L).containsEntry("attempt_log_duplicate_lines", 1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 발행률은_고정_측정_창으로_나누고_수신은_메시지별_첫_수신만_센다() {
        Map<String, Object> summary = RunReport.summarize(RunInputs.read(runDir));
        Map<String, Object> rates = (Map<String, Object>) summary.get("rates");

        assertThat((Map<String, Object>) summary.get("window")).containsEntry("seconds", 8.0);
        // [1s, 9s) 안 요청: m1~m8 = 8건.
        assertThat(rates).containsEntry("requested_per_s", 1.0);
        // 첫 수신이 창 안인 메시지: w1·m1·m2·m3·m6·m7 = 6건. 9s 의 m1 재전달은 다시 세지 않는다.
        assertThat(rates).containsEntry("received_per_s", 6 / 8.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 재시도는_메시지별_실패_시도로_세고_지연은_재시도_여부로_나눈다() {
        Map<String, Object> summary = RunReport.summarize(RunInputs.read(runDir));
        Map<String, Object> retries = (Map<String, Object>) summary.get("retries");
        Map<String, Object> latency = (Map<String, Object>) summary.get("latency_us");

        // 4번 모두 실패해 격리된 m3 는 재시도 3회다(시도 수 − 1).
        assertThat((Map<String, Long>) retries.get("messages_by_retries"))
                .containsOnly(Map.entry("0", 4L), Map.entry("1", 1L), Map.entry("3", 1L));
        assertThat(retries).containsEntry("retried_messages", 2L).containsEntry("retry_success_rate", 0.5);

        Map<String, Object> noRetry = (Map<String, Object>) latency.get("e2e_no_retry");
        Map<String, Object> withRetry = (Map<String, Object>) latency.get("e2e_with_retry");
        // 측정 구간의 m1·m6·m7 (예열 w1 제외), 재시도한 m2.
        assertThat(noRetry).containsEntry("count", 3L);
        assertThat(withRetry).containsEntry("count", 1L);
        assertThat(((Number) withRetry.get("max")).longValue()).isBetween(1_030_000L, 1_031_000L);
        // 원본 히스토그램을 되살리면 같은 표본 수다.
        Histogram decoded = Latency.decode((String) ((Map<String, Object>) noRetry.get("histogram")).get("data"));
        assertThat(decoded.getTotalCount()).isEqualTo(3);
        // 격리 표로 간 m3: 요청 3s → 격리 10.015s.
        assertThat(((Number) ((Map<String, Object>) latency.get("final_failure")).get("max")).longValue())
                .isBetween(7_010_000L, 7_020_000L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void kill_회차와_표본_부족과_발생기_미달을_한계로_적고_원본_해시를_남긴다() {
        Map<String, Object> summary = RunReport.summarize(RunInputs.read(runDir));
        List<String> limits = (List<String>) summary.get("limits");

        assertThat(limits).anyMatch(line -> line.contains("하한값"))
                .anyMatch(line -> line.contains("분류 오차 가능"))
                .anyMatch(line -> line.contains("표본 3건"))
                .anyMatch(line -> line.contains("목표 발행률"))
                .anyMatch(line -> line.contains("누락·손상"));
        assertThat((Map<String, String>) summary.get("files")).containsKeys(
                "published.csv", "attempts-1-100.csv", "attempts-2-200.csv", "bench_message.tsv", "quarantine.tsv",
                "generator.json", "run.json");
        Map<String, Object> series = (Map<String, Object>) summary.get("timeseries_10s");
        assertThat((long[]) series.get("requested")).containsExactly(9, 0);
        assertThat((long[]) series.get("quarantined")).containsExactly(0, 2);
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(runDir.resolve(name), content, StandardCharsets.UTF_8);
    }
}
