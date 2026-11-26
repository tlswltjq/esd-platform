package com.stove.bench.messaging.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 회차 폴더의 원본(E13·결정 233). 파일 이름과 열은 발생기·시험 앱·측정 스크립트가 쓰는 것과 같다.
 *
 * <ul>
 *   <li>{@code published.csv} — 발생기({@code LoadGenerator})</li>
 *   <li>{@code attempts-*.csv} — 시험 앱 프로세스마다 하나({@code FileAttemptLog})</li>
 *   <li>{@code bench_message.tsv}·{@code quarantine.tsv} — 측정 스크립트가 DB에서 내보냄(머리 행 없음, 탭 구분)</li>
 *   <li>{@code dlt.csv} — {@code TopicDump}(없으면 DLT 0건)</li>
 *   <li>{@code generator.json}·{@code run.json} — 발생기 결과, 측정 스크립트의 환경·사건 기록</li>
 * </ul>
 */
public record RunInputs(List<Published> published, List<AttemptRow> attempts, List<TableRow> table,
                        List<QuarantineRow> quarantine, List<DltRow> dlt, Map<String, Object> generator,
                        Map<String, Object> run, List<Path> files, AttemptLogDamage attemptDamage) {

    /**
     * 수신 시도 기록의 손상(시험 앱 {@code FileAttemptLog}가 쓰기 실패 뒤 다시 쓴 흔적). 끊긴 줄은 버리고, 같은 줄이 두 번이면 하나만 쓴다.
     *
     * @param malformedLines 열 수가 맞지 않아 버린 줄.
     * @param duplicateLines 같은 내용이 이미 있어 버린 줄.
     */
    public record AttemptLogDamage(long malformedLines, long duplicateLines) {
    }

    public record Published(String eventId, String key, String phase, String fail, int payloadBytes, long scheduledUs,
                            long requestedUs, long ackNs, int partition, long offset, String error) {
        boolean acked() {
            return error.isEmpty();
        }

        boolean measured() {
            return phase.equals("measure");
        }

        long ackedAtUs() {
            return requestedUs + ackNs / 1_000;
        }
    }

    public record AttemptRow(String eventId, int attempt, int partition, long offset, long receivedUs, long finishedUs,
                             String outcome, String error) {
    }

    public record TableRow(long id, String eventId, String key, int attempt, long completedUs) {
    }

    /** @param keyStopped 같은 키가 멈춰 있어 리스너를 거치지 않고 쌓인 행({@code failure = KEY_STOPPED}). */
    public record QuarantineRow(String eventId, String status, boolean keyStopped, long quarantinedUs) {
    }

    public record DltRow(String eventId, int partition, long offset, long timestampMs) {
    }

    public static RunInputs read(Path runDir) {
        List<Path> files = new ArrayList<>();
        List<Published> published = new ArrayList<>();
        for (String[] f : csv(runDir.resolve("published.csv"), 11, files)) {
            published.add(new Published(f[0], f[1], f[2], f[3], Integer.parseInt(f[4]), Long.parseLong(f[5]),
                    Long.parseLong(f[6]), Long.parseLong(f[7]), Integer.parseInt(f[8]), Long.parseLong(f[9]), f[10]));
        }
        List<AttemptRow> attempts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long malformed = 0;
        long duplicates = 0;
        for (Path file : attemptFiles(runDir)) {
            List<String> lines = lines(file, files);
            for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] f = line.split(",", -1);
                // 마지막 칸이 끝 표시(#)인 줄만 끝까지 쓰인 줄이다(FileAttemptLog 가 쓰다 끊긴 줄을 가린다).
                AttemptRow row = f.length == 9 && f[8].equals("#") ? attemptRow(f) : null;
                if (row == null) {
                    malformed++;
                } else if (!seen.add(line)) {
                    duplicates++;
                } else {
                    attempts.add(row);
                }
            }
        }
        List<TableRow> table = new ArrayList<>();
        for (String[] f : tsv(runDir.resolve("bench_message.tsv"), 5, files)) {
            table.add(new TableRow(Long.parseLong(f[0]), f[1], f[2], Integer.parseInt(f[3]), Long.parseLong(f[4])));
        }
        List<QuarantineRow> quarantine = new ArrayList<>();
        for (String[] f : tsv(runDir.resolve("quarantine.tsv"), 4, files)) {
            quarantine.add(new QuarantineRow(f[0], f[1], f[2].equals("1"), Long.parseLong(f[3])));
        }
        List<DltRow> dlt = new ArrayList<>();
        Path dltFile = runDir.resolve("dlt.csv");
        if (Files.exists(dltFile)) {
            for (String[] f : csv(dltFile, 4, files)) {
                dlt.add(new DltRow(f[0], Integer.parseInt(f[1]), Long.parseLong(f[2]), Long.parseLong(f[3])));
            }
        }
        Map<String, Object> generator = json(runDir.resolve("generator.json"), files);
        Map<String, Object> run = json(runDir.resolve("run.json"), files);
        return new RunInputs(published, attempts, table, quarantine, dlt, generator, run, files,
                new AttemptLogDamage(malformed, duplicates));
    }

    /** 숫자 칸이 숫자가 아니면(끊긴 줄이 다음 줄과 붙은 경우 등) {@code null}. */
    private static AttemptRow attemptRow(String[] f) {
        try {
            return new AttemptRow(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), Long.parseLong(f[3]),
                    Long.parseLong(f[4]), Long.parseLong(f[5]), f[6], f[7]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<Path> attemptFiles(Path runDir) {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(runDir, "attempts-*.csv")) {
            stream.forEach(found::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        found.sort(null);
        return found;
    }

    /** 머리 행이 있는 쉼표 구분. 값에 쉼표가 없다(UUID·숫자·예외 클래스 이름). 빈 마지막 칸을 지키려고 -1로 나눈다. */
    private static List<String[]> csv(Path file, int columns, List<Path> files) {
        List<String> lines = lines(file, files);
        return split(lines.subList(Math.min(1, lines.size()), lines.size()), ",", columns, file);
    }

    /** {@code mysql -B -N} 출력: 머리 행 없음, 탭 구분. */
    private static List<String[]> tsv(Path file, int columns, List<Path> files) {
        return split(lines(file, files), "\t", columns, file);
    }

    private static List<String[]> split(List<String> lines, String separator, int columns, Path file) {
        List<String[]> rows = new ArrayList<>();
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            String[] fields = line.split(separator, -1);
            if (fields.length != columns) {
                throw new IllegalArgumentException("%s: 열이 %d개가 아니다: %s".formatted(file.getFileName(), columns, line));
            }
            rows.add(fields);
        }
        return rows;
    }

    private static List<String> lines(Path file, List<Path> files) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            files.add(file);
            return lines;
        } catch (IOException e) {
            throw new UncheckedIOException("원본을 읽을 수 없다: " + file, e);
        }
    }

    private static Map<String, Object> json(Path file, List<Path> files) {
        try {
            Map<String, Object> value = new ObjectMapper().readValue(file.toFile(), new TypeReference<LinkedHashMap<String, Object>>() { });
            files.add(file);
            return value;
        } catch (IOException e) {
            throw new UncheckedIOException("원본을 읽을 수 없다: " + file, e);
        }
    }
}
