package com.stove.bench.messaging.app.adapter.file;

import com.stove.bench.messaging.app.core.domain.Attempt;
import com.stove.bench.messaging.app.core.port.AttemptLog;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * 수신 시도를 메모리 큐에 쌓고 별도 스레드가 {@code flushMillis}마다 회차 폴더의 CSV에 덧붙인다(E13). 리스너 스레드는 큐에 넣기만 한다.
 *
 * <p>프로세스를 정상 종료하면 남은 것을 마저 쓴다({@link #close()}). 강제 종료(kill)면 마지막 한 칸이 사라질 수 있다 —
 * E13은 그 회차의 수신·재시도 수를 하한값으로 적는다. 프로세스마다 다른 파일({@code attempts-<시작 시각>-<pid>.csv})에 쓰므로
 * 재시작한 회차도 앞 프로세스의 기록을 덮지 않는다.
 */
@Slf4j
public class FileAttemptLog implements AttemptLog, AutoCloseable {

    private final Queue<Attempt> pending = new ConcurrentLinkedQueue<>();
    private final Path file;
    private final ScheduledExecutorService flusher;
    /** 쓰기에 실패해 다시 쓸 묶음. {@link #flush()}만 바꾼다(동기화). */
    private List<Attempt> retry = List.of();
    private long writeFailures;

    public FileAttemptLog(Path runDir, String processTag, long flushMillis) {
        try {
            Files.createDirectories(runDir);
            this.file = runDir.resolve("attempts-" + processTag + ".csv");
            Files.writeString(file, Attempt.CSV_HEADER + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("수신 시도 기록 파일을 만들 수 없다: " + runDir, e);
        }
        this.flusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bench-attempt-log");
            thread.setDaemon(true);
            return thread;
        });
        flusher.scheduleWithFixedDelay(this::flushQuietly, flushMillis, flushMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void record(Attempt attempt) {
        pending.add(attempt);
    }

    public Path file() {
        return file;
    }

    /**
     * 지금까지 쌓인 시도를 파일에 덧붙인다. 덧붙인 줄 수를 돌려준다.
     *
     * <p>쓰기가 실패하면 그 묶음을 버리지 않고 다음 내보내기에서 다시 쓴다(PR #253 리뷰 R1). 실패한 쓰기가 줄 중간에서 끊겼을 수 있으므로
     * 다시 쓸 때는 줄바꿈부터 써서 끊긴 줄을 닫는다 — 요약기는 열 수가 맞지 않는 줄을 버리고 세며, 같은 줄이 두 번 들어가면 하나로 본다.
     */
    public synchronized int flush() {
        List<Attempt> batch = new ArrayList<>(retry);
        Attempt next;
        while ((next = pending.poll()) != null) {
            batch.add(next);
        }
        if (batch.isEmpty()) {
            return 0;
        }
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
            if (!retry.isEmpty()) {
                writer.write('\n');
            }
            for (Attempt attempt : batch) {
                writer.write(attempt.toCsv());
                writer.write('\n');
            }
        } catch (IOException e) {
            retry = batch;
            writeFailures++;
            throw new UncheckedIOException("수신 시도 기록을 쓸 수 없다(다음 내보내기에서 다시 쓴다): " + file, e);
        }
        retry = List.of();
        return batch.size();
    }

    /** 쓰기 실패 횟수. 0이 아니면 그 회차의 수신 시도 기록이 늦게 쓰였거나 빠졌을 수 있다. */
    public synchronized long writeFailures() {
        return writeFailures;
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException e) {
            // 기록 실패로 측정이 조용히 틀리면 안 된다. 묶음은 다음에 다시 쓰고, 요약은 완료됐는데 시도 기록이 없는 메시지로 드러낸다.
            log.error("수신 시도 기록 실패 file={} failures={}", file, writeFailures(), e);
        }
    }

    @Override
    public void close() {
        flusher.shutdown();
        try {
            if (!flusher.awaitTermination(5, TimeUnit.SECONDS)) {
                flusher.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 마지막 내보내기도 실패하면 종료를 실패로 남긴다(측정 스크립트가 종료 코드와 로그로 본다).
        flush();
    }
}
