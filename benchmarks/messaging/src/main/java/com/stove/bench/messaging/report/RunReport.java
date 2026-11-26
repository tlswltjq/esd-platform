package com.stove.bench.messaging.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.stove.bench.messaging.report.RunInputs.AttemptRow;
import com.stove.bench.messaging.report.RunInputs.DltRow;
import com.stove.bench.messaging.report.RunInputs.Published;
import com.stove.bench.messaging.report.RunInputs.QuarantineRow;
import com.stove.bench.messaging.report.RunInputs.TableRow;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 회차 원본으로 {@code summary.json}을 만든다(E13 지표 정의·결정 233). 판정(결정 231)은 하지 않는다 — 판정식은 여러 회차를 함께 봐야 하고
 * 기준선이 정해진 뒤에 적용한다. 여기서는 판정에 쓸 값과 그 값의 한계만 낸다.
 *
 * <p>집계 범위: 지연·발행률은 측정 구간 메시지({@code phase=measure})만, 정합성(유실·중복·순서)은 예열 포함 모든 메시지다.
 * 처리율 시계열은 메시지가 아니라 시각으로 센다(그 칸에 커밋된 행 수).
 *
 * <p>{@code --run-dir=<회차 폴더>}
 */
public final class RunReport {

    static final long BUCKET_US = 10_000_000;
    /** 결정 231: p99 판정에 필요한 회차별 최소 표본. */
    static final long MIN_P99_SAMPLES = 1_000;

    private RunReport() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1 || !args[0].startsWith("--run-dir=")) {
            throw new IllegalArgumentException("사용법: RunReport --run-dir=<회차 폴더>");
        }
        Path runDir = Path.of(args[0].substring("--run-dir=".length()));
        Map<String, Object> summary = summarize(RunInputs.read(runDir));
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(runDir.resolve("summary.json").toFile(), summary);
        System.out.println(new ObjectMapper().writeValueAsString(summary.get("integrity")));
    }

    public static Map<String, Object> summarize(RunInputs in) {
        Map<String, List<AttemptRow>> attempts = group(in.attempts(), AttemptRow::eventId);
        attempts.values().forEach(rows -> rows.sort(Comparator.comparingLong(AttemptRow::receivedUs)));
        Map<String, List<TableRow>> table = group(in.table(), TableRow::eventId);
        table.values().forEach(rows -> rows.sort(Comparator.comparingLong(TableRow::id)));
        Map<String, List<QuarantineRow>> quarantine = group(in.quarantine(), QuarantineRow::eventId);
        Map<String, List<DltRow>> dlt = group(in.dlt(), DltRow::eventId);
        List<Published> measured = in.published().stream().filter(Published::measured).toList();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schema", 1);
        summary.put("run", in.run());
        summary.put("generator", in.generator());
        Map<String, Object> window = window(in);
        summary.put("window", window);
        summary.put("counts", counts(in, measured, attempts, table, quarantine, dlt));
        summary.put("rates", rates(in, window, attempts, table));
        summary.put("latency_us", latencies(measured, attempts, table, quarantine, dlt));
        summary.put("retries", retries(in.published(), attempts, table));
        Map<String, Object> integrity = integrity(in.published(), attempts, table, quarantine, dlt);
        summary.put("integrity", integrity);
        summary.put("timeseries_10s", timeseries(in, attempts, table, window));
        summary.put("limits", limits(in, summary));
        summary.put("files", hashes(in.files()));
        return summary;
    }

    /**
     * 측정 창 {@code [start_us, end_us)}: 발생기가 예정 시각으로 고정한 창(generator.json {@code measured.window_*}, PR #253 리뷰 R2).
     * 마지막 예정 시각까지가 아니라 측정 시간 전체다 — 그래야 발행률·처리율을 측정 시간으로 나눌 때 마지막 간격이 빠지지 않는다.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> window(RunInputs in) {
        Map<String, Object> window = new LinkedHashMap<>();
        Object measured = in.generator().get("measured");
        if (measured instanceof Map<?, ?> values && values.get("window_start_us") instanceof Number start
                && values.get("window_end_us") instanceof Number end && end.longValue() > start.longValue()) {
            window.put("start_us", start.longValue());
            window.put("end_us", end.longValue());
            window.put("seconds", (end.longValue() - start.longValue()) / 1e6);
            return window;
        }
        window.put("start_us", null);
        window.put("end_us", null);
        window.put("seconds", null);
        return window;
    }

    private static Map<String, Object> counts(RunInputs in, List<Published> measured, Map<String, List<AttemptRow>> attempts,
                                              Map<String, List<TableRow>> table, Map<String, List<QuarantineRow>> quarantine,
                                              Map<String, List<DltRow>> dlt) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("requested", in.published().size());
        counts.put("requested_measure", measured.size());
        counts.put("acked", in.published().stream().filter(Published::acked).count());
        counts.put("ack_failed", in.published().stream().filter(p -> !p.acked()).count());
        counts.put("received", attempts.size());
        counts.put("attempts", in.attempts().size());
        counts.put("failed_attempts", in.attempts().stream().filter(a -> a.outcome().equals("FAILED")).count());
        counts.put("completed", table.size());
        counts.put("quarantined_failed", quarantine.values().stream().filter(rows -> rows.stream().anyMatch(q -> !q.keyStopped())).count());
        counts.put("quarantined_key_stopped", quarantine.values().stream().filter(rows -> rows.stream().allMatch(QuarantineRow::keyStopped)).count());
        counts.put("dead_lettered", dlt.size());
        counts.put("dlt_records", in.dlt().size());
        counts.put("completed_without_attempt_record", table.keySet().stream().filter(id -> !attempts.containsKey(id)).count());
        counts.put("attempt_log_malformed_lines", in.attemptDamage().malformedLines());
        counts.put("attempt_log_duplicate_lines", in.attemptDamage().duplicateLines());
        return counts;
    }

    private static Map<String, Object> rates(RunInputs in, Map<String, Object> window, Map<String, List<AttemptRow>> attempts,
                                             Map<String, List<TableRow>> table) {
        Map<String, Object> rates = new LinkedHashMap<>();
        Object start = window.get("start_us");
        Object end = window.get("end_us");
        if (start == null) {
            rates.put("note", "측정 구간이 없어 발행률을 셀 수 없다");
            return rates;
        }
        long from = (long) start;
        long to = (long) end;
        double seconds = (to - from) / 1e6;
        long requested = in.published().stream().filter(p -> inWindow(p.requestedUs(), from, to)).count();
        long acked = in.published().stream().filter(Published::acked).filter(p -> inWindow(p.ackedAtUs(), from, to)).count();
        // 메시지별 첫 수신만 센다. 시도 번호 1은 프로세스를 다시 띄우면 재전달에도 붙는다(PR #253 리뷰 R5).
        long received = attempts.values().stream().filter(rows -> inWindow(rows.get(0).receivedUs(), from, to)).count();
        long completed = table.values().stream().filter(rows -> inWindow(rows.get(0).completedUs(), from, to)).count();
        long failed = in.attempts().stream().filter(a -> a.outcome().equals("FAILED") && inWindow(a.finishedUs(), from, to)).count();
        rates.put("requested_per_s", requested / seconds);
        rates.put("acked_per_s", acked / seconds);
        rates.put("received_per_s", received / seconds);
        rates.put("completed_per_s", completed / seconds);
        rates.put("failed_attempts_per_s", failed / seconds);
        rates.put("acked_minus_completed_per_s", (acked - completed) / seconds);
        return rates;
    }

    /** 반열린 창 {@code [from, to)}. */
    private static boolean inWindow(long value, long from, long to) {
        return value >= from && value < to;
    }

    private static Map<String, Object> latencies(List<Published> measured, Map<String, List<AttemptRow>> attempts,
                                                 Map<String, List<TableRow>> table, Map<String, List<QuarantineRow>> quarantine,
                                                 Map<String, List<DltRow>> dlt) {
        Latency ack = new Latency();
        Latency broker = new Latency();
        Latency processing = new Latency();
        Latency noRetry = new Latency();
        Latency withRetry = new Latency();
        Latency all = new Latency();
        Latency finalFailure = new Latency();
        for (Published message : measured) {
            if (!message.acked()) {
                continue;
            }
            ack.record(message.ackNs() / 1_000);
            List<AttemptRow> rows = attempts.getOrDefault(message.eventId(), List.of());
            AttemptRow applied = rows.stream().filter(a -> a.outcome().equals("APPLIED")).findFirst().orElse(null);
            if (!rows.isEmpty()) {
                broker.record(rows.get(0).receivedUs() - message.ackedAtUs());
            }
            if (applied != null) {
                processing.record(applied.finishedUs() - rows.get(0).receivedUs());
                boolean retried = rows.stream().anyMatch(a -> a.outcome().equals("FAILED") && a.receivedUs() <= applied.receivedUs());
                (retried ? withRetry : noRetry).record(applied.finishedUs() - message.requestedUs());
            }
            List<TableRow> rowsInTable = table.get(message.eventId());
            if (rowsInTable != null) {
                all.record(rowsInTable.get(0).completedUs() - message.requestedUs());
            }
            List<QuarantineRow> held = quarantine.getOrDefault(message.eventId(), List.of());
            held.stream().filter(q -> !q.keyStopped()).findFirst()
                    .ifPresent(q -> finalFailure.record(q.quarantinedUs() - message.requestedUs()));
            List<DltRow> dead = dlt.getOrDefault(message.eventId(), List.of());
            if (!dead.isEmpty()) {
                finalFailure.record(dead.get(0).timestampMs() * 1_000 - message.requestedUs());
            }
        }
        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("ack", ack.summary());
        latency.put("broker", broker.summary());
        latency.put("processing", processing.summary());
        latency.put("e2e_no_retry", noRetry.summary());
        latency.put("e2e_with_retry", withRetry.summary());
        latency.put("e2e_all", all.summary());
        latency.put("final_failure", finalFailure.summary());
        return latency;
    }

    /**
     * 재시도 횟수 = 메시지별 시도 수 − 1(E13 지표 정의, PR #253 리뷰 R3). 시도는 첫 반영까지만 센다 — 반영 뒤 재전달(중복 전달)은 재시도가 아니다.
     * 4번 모두 실패해 격리된 메시지는 재시도 3회다. 재시도 성공률의 분모는 두 번째 시도가 실제로 있었던 메시지다.
     */
    private static Map<String, Object> retries(List<Published> published, Map<String, List<AttemptRow>> attempts,
                                               Map<String, List<TableRow>> table) {
        Map<Integer, Long> perMessage = new TreeMap<>();
        long retried = 0;
        long retrySucceeded = 0;
        for (Published message : published) {
            List<AttemptRow> rows = attempts.get(message.eventId());
            if (rows == null) {
                continue;
            }
            int retriesOfMessage = attemptsUntilApplied(rows) - 1;
            perMessage.merge(retriesOfMessage, 1L, Long::sum);
            if (retriesOfMessage > 0) {
                retried++;
                if (table.containsKey(message.eventId())) {
                    retrySucceeded++;
                }
            }
        }
        Map<String, Object> retries = new LinkedHashMap<>();
        Map<String, Long> byRetries = new LinkedHashMap<>();
        perMessage.forEach((count, messages) -> byRetries.put(Integer.toString(count), messages));
        retries.put("messages_by_retries", byRetries);
        retries.put("retried_messages", retried);
        retries.put("retry_succeeded", retrySucceeded);
        retries.put("retry_success_rate", retried == 0 ? null : (double) retrySucceeded / retried);
        return retries;
    }

    /** 받은 순서로 첫 {@code APPLIED}·{@code DUPLICATE}까지의 시도 수. 반영이 없으면(격리·진행 중) 모든 실패 시도 수. */
    static int attemptsUntilApplied(List<AttemptRow> rows) {
        int count = 0;
        for (AttemptRow row : rows) {
            count++;
            if (!row.outcome().equals("FAILED")) {
                break;
            }
        }
        return count;
    }

    private static Map<String, Object> integrity(List<Published> published, Map<String, List<AttemptRow>> attempts,
                                                 Map<String, List<TableRow>> table, Map<String, List<QuarantineRow>> quarantine,
                                                 Map<String, List<DltRow>> dlt) {
        long lost = 0;
        List<String> lostSample = new ArrayList<>();
        long completedAndFailed = 0;
        long expectedFailures = 0;
        long injectedButCompleted = 0;
        long unexpectedFailures = 0;
        long keyStopped = 0;
        long unackedButDelivered = 0;
        for (Published message : published) {
            boolean completed = table.containsKey(message.eventId());
            List<QuarantineRow> held = quarantine.getOrDefault(message.eventId(), List.of());
            boolean failedFinal = held.stream().anyMatch(q -> !q.keyStopped()) || dlt.containsKey(message.eventId());
            boolean stopped = !held.isEmpty() && held.stream().allMatch(QuarantineRow::keyStopped);
            boolean permanent = message.fail().equals("permanent");
            if (!message.acked()) {
                if (completed || failedFinal || stopped) {
                    unackedButDelivered++;
                }
                continue;
            }
            if (completed && (failedFinal || stopped)) {
                completedAndFailed++;
            }
            if (permanent) {
                expectedFailures++;
                if (completed) {
                    injectedButCompleted++;
                }
            } else if (failedFinal) {
                unexpectedFailures++;
            }
            if (stopped) {
                keyStopped++;
            }
            if (!completed && !failedFinal && !stopped) {
                lost++;
                if (lostSample.size() < 20) {
                    lostSample.add(message.eventId());
                }
            }
        }
        long duplicateDeliveredMessages = 0;
        long duplicateDeliveries = 0;
        for (List<AttemptRow> rows : attempts.values()) {
            AttemptRow applied = rows.stream().filter(a -> a.outcome().equals("APPLIED")).findFirst().orElse(null);
            long after = rows.stream().filter(a -> a.outcome().equals("DUPLICATE")
                    || (applied != null && a != applied && a.receivedUs() > applied.receivedUs())).count();
            if (after > 0) {
                duplicateDeliveredMessages++;
                duplicateDeliveries += after;
            }
        }
        long duplicateApplied = table.values().stream().filter(rows -> rows.size() > 1).count();
        Map<String, Object> order = orderViolations(published, table);

        Map<String, Object> integrity = new LinkedHashMap<>();
        integrity.put("lost", lost);
        integrity.put("lost_sample", lostSample);
        integrity.put("duplicate_delivered_messages", duplicateDeliveredMessages);
        integrity.put("duplicate_deliveries", duplicateDeliveries);
        integrity.put("duplicate_applied", duplicateApplied);
        integrity.putAll(order);
        integrity.put("expected_final_failures", expectedFailures);
        integrity.put("injected_but_completed", injectedButCompleted);
        integrity.put("unexpected_final_failures", unexpectedFailures);
        integrity.put("held_behind_stopped_key", keyStopped);
        integrity.put("completed_and_failed", completedAndFailed);
        integrity.put("unacked_but_delivered", unackedButDelivered);
        return integrity;
    }

    /**
     * 같은 키 순서(계약 3): 키마다 시험 표 행을 커밋 순서(자동 증가 id)로 놓고, 발행 offset이 줄어든 곳을 센다.
     * 같은 키는 같은 파티션이라 offset이 발행 순서다.
     */
    private static Map<String, Object> orderViolations(List<Published> published, Map<String, List<TableRow>> table) {
        Map<String, Published> byId = new HashMap<>();
        published.forEach(p -> byId.put(p.eventId(), p));
        Map<String, List<TableRow>> byKey = new HashMap<>();
        table.values().forEach(rows -> byKey.computeIfAbsent(rows.get(0).key(), k -> new ArrayList<>()).add(rows.get(0)));
        long violations = 0;
        long keys = 0;
        for (List<TableRow> rows : byKey.values()) {
            rows.sort(Comparator.comparingLong(TableRow::id));
            long highest = -1;
            long here = 0;
            for (TableRow row : rows) {
                Published message = byId.get(row.eventId());
                if (message == null) {
                    continue;
                }
                if (message.offset() < highest) {
                    here++;
                }
                highest = Math.max(highest, message.offset());
            }
            violations += here;
            if (here > 0) {
                keys++;
            }
        }
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("order_violations", violations);
        order.put("keys_with_order_violation", keys);
        return order;
    }

    private static Map<String, Object> timeseries(RunInputs in, Map<String, List<AttemptRow>> attempts,
                                                  Map<String, List<TableRow>> table, Map<String, Object> window) {
        Map<String, Object> series = new LinkedHashMap<>();
        if (in.published().isEmpty()) {
            return series;
        }
        long origin = in.published().stream().mapToLong(Published::scheduledUs).min().orElseThrow();
        Map<String, List<Long>> events = new LinkedHashMap<>();
        events.put("requested", in.published().stream().map(Published::requestedUs).toList());
        events.put("acked", in.published().stream().filter(Published::acked).map(Published::ackedAtUs).toList());
        events.put("received", attempts.values().stream().map(rows -> rows.get(0).receivedUs()).toList());
        events.put("completed", table.values().stream().map(rows -> rows.get(0).completedUs()).toList());
        events.put("failed_attempts", in.attempts().stream().filter(a -> a.outcome().equals("FAILED")).map(AttemptRow::finishedUs).toList());
        events.put("quarantined", in.quarantine().stream().map(QuarantineRow::quarantinedUs).toList());
        events.put("dead_lettered", in.dlt().stream().map(d -> d.timestampMs() * 1_000).toList());
        long last = events.values().stream().flatMap(List::stream).mapToLong(Long::longValue).max().orElse(origin);
        int buckets = (int) ((last - origin) / BUCKET_US) + 1;
        series.put("origin_us", origin);
        series.put("bucket_us", BUCKET_US);
        Object start = window.get("start_us");
        Object end = window.get("end_us");
        series.put("measure_from_bucket", start == null ? null : ((long) start - origin) / BUCKET_US);
        series.put("measure_to_bucket", end == null ? null : ((long) end - 1 - origin) / BUCKET_US);
        events.forEach((name, times) -> {
            long[] counts = new long[buckets];
            for (long time : times) {
                long index = (time - origin) / BUCKET_US;
                if (index >= 0 && index < buckets) {
                    counts[(int) index]++;
                }
            }
            series.put(name, counts);
        });
        return series;
    }

    @SuppressWarnings("unchecked")
    private static List<String> limits(RunInputs in, Map<String, Object> summary) {
        List<String> limits = new ArrayList<>();
        Object kills = in.run().get("kills_us");
        if (kills instanceof List<?> list && !list.isEmpty()) {
            limits.add("kill 회차: 수신·재시도·중복 전달 수는 하한값이다(kill 전 1초의 시도 기록이 사라질 수 있다)");
            limits.add("kill 회차: broker·processing 지연은 kill 직전 표본 누락 가능");
            limits.add("kill 회차: e2e_no_retry·e2e_with_retry 는 분류 오차 가능 — 재시도로 나누지 않은 e2e_all 을 함께 본다");
        }
        if (in.attempts().isEmpty() && !in.table().isEmpty()) {
            limits.add("수신 시도 기록이 없다 — 수신·재시도·broker·processing 값을 쓰지 않는다");
        }
        Map<String, Object> counts = (Map<String, Object>) summary.get("counts");
        long withoutRecord = (long) counts.get("completed_without_attempt_record");
        if (withoutRecord > 0 || in.attemptDamage().malformedLines() > 0) {
            // kill 없이 이 값이 0이 아니면 시도 기록 쓰기가 실패한 것이다(FileAttemptLog 로그). 그 회차는 다시 잰다.
            limits.add("수신 시도 기록 누락·손상: 완료됐는데 시도 기록이 없는 메시지 %d건, 버린 줄 %d — 수신·재시도·broker·processing 은 불완전%s"
                    .formatted(withoutRecord, in.attemptDamage().malformedLines(),
                            kills instanceof List<?> killed && !killed.isEmpty() ? "" : "(kill 없는 회차라 무효로 본다)"));
        }
        Map<String, Object> latency = (Map<String, Object>) summary.get("latency_us");
        long samples = (long) ((Map<String, Object>) latency.get("e2e_no_retry")).get("count");
        if (samples < MIN_P99_SAMPLES) {
            limits.add("e2e_no_retry 표본 %d건 < %d — p99 판정에 쓰지 않는다(결정 231)".formatted(samples, MIN_P99_SAMPLES));
        }
        Object measured = in.generator().get("measured");
        if (measured instanceof Map<?, ?> values && values.get("achieved_rate") instanceof Number achieved
                && values.get("target_rate") instanceof Number target && achieved.doubleValue() < target.doubleValue() * 0.99) {
            limits.add("발생기가 목표 발행률의 99%%를 내지 못했다(%.1f / %.1f msg/s) — 최대 성능 확정에 쓰지 않는다(결정 231)"
                    .formatted(achieved.doubleValue(), target.doubleValue()));
        }
        return limits;
    }

    private static Map<String, String> hashes(List<Path> files) {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Path file : files) {
            hashes.put(file.getFileName().toString(), sha256(file));
        }
        return hashes;
    }

    static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> Map<String, List<T>> group(List<T> rows, Function<T, String> key) {
        Map<String, List<T>> grouped = new HashMap<>();
        for (T row : rows) {
            grouped.computeIfAbsent(key.apply(row), k -> new ArrayList<>()).add(row);
        }
        return grouped;
    }
}
