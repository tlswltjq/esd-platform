package com.stove.bench.messaging.load;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * 발행 기록 {@code published.csv}. producer 콜백(I/O 스레드)과 발행 루프는 큐에 넣기만 하고, 쓰기 스레드가 파일에 옮긴다 —
 * 파일 쓰기가 발행 일정을 늦추지 않게 한다.
 */
final class PublishedLog implements AutoCloseable {

    static final String CSV_HEADER =
            "event_id,key,phase,fail,payload_bytes,scheduled_us,requested_us,ack_ns,partition,offset,error";

    private final Queue<String> lines = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final BufferedWriter writer;
    private final Thread drainer;

    PublishedLog(Path file) {
        try {
            Files.createDirectories(file.getParent());
            this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            writer.write(CSV_HEADER);
            writer.write('\n');
        } catch (IOException e) {
            throw new UncheckedIOException("발행 기록 파일을 만들 수 없다: " + file, e);
        }
        this.drainer = new Thread(this::drainLoop, "bench-published-log");
        drainer.setDaemon(true);
        drainer.start();
    }

    /** 확인 콜백이 끝난 메시지 한 건. {@code error}가 비면 성공이다. */
    void add(String eventId, String key, String phase, String fail, int payloadBytes, long scheduledMicros,
             long requestedMicros, long ackNanos, int partition, long offset, String error) {
        lines.add(String.join(",", eventId, key, phase, fail, Integer.toString(payloadBytes),
                Long.toString(scheduledMicros), Long.toString(requestedMicros), Long.toString(ackNanos),
                Integer.toString(partition), Long.toString(offset), error));
    }

    private void drainLoop() {
        while (!closed.get()) {
            drain();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(200));
        }
    }

    private synchronized void drain() {
        String line;
        try {
            while ((line = lines.poll()) != null) {
                writer.write(line);
                writer.write('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException("발행 기록을 쓸 수 없다", e);
        }
    }

    @Override
    public void close() {
        closed.set(true);
        try {
            drainer.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        drain();
        try {
            writer.close();
        } catch (IOException e) {
            throw new UncheckedIOException("발행 기록을 닫을 수 없다", e);
        }
    }
}
