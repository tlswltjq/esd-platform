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

    /** 지금까지 쌓인 시도를 파일에 덧붙인다. 덧붙인 줄 수를 돌려준다. */
    public synchronized int flush() {
        List<Attempt> batch = new ArrayList<>();
        Attempt next;
        while ((next = pending.poll()) != null) {
            batch.add(next);
        }
        if (batch.isEmpty()) {
            return 0;
        }
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
            for (Attempt attempt : batch) {
                writer.write(attempt.toCsv());
                writer.write('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException("수신 시도 기록을 쓸 수 없다: " + file, e);
        }
        return batch.size();
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException e) {
            // 기록 실패로 측정이 조용히 틀리면 안 된다. 로그로 남기고, 요약은 시도 수가 처리 수보다 적은 것으로 드러난다.
            log.error("수신 시도 기록 실패 file={}", file, e);
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
        flush();
    }
}
