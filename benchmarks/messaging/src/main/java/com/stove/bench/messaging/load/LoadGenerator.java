package com.stove.bench.messaging.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.stove.bench.messaging.app.core.domain.BenchMessage;
import com.stove.bench.messaging.app.core.domain.FailurePlan;
import com.stove.common.contract.EventHeaders;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.HdrHistogram.Histogram;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * 층 2 open-loop 부하 발생기(E13·결정 234). 시험 앱과 다른 프로세스로 돈다.
 *
 * <p>producer 설정은 운영 앱과 같다({@code acks=all}·{@code enable.idempotence=true}·{@code retries=3}·문자열 직렬화).
 * 다른 값은 {@code --producer.<키>=<값>}으로만 바꾸고 {@code generator.json}에 남긴다.
 *
 * <p>출력: {@code published.csv}(메시지별 예정·요청 시각, 확인 지연, 파티션·offset, 오류)와 {@code generator.json}
 * (목표·달성 발행률, 일정 지연 분포, 설정). 달성률이 목표에 못 미친 회차는 최대 성능 확정에 쓰지 않는다(결정 231).
 */
public final class LoadGenerator {

    private LoadGenerator() {
    }

    public static void main(String[] args) throws Exception {
        LoadOptions options = LoadOptions.parse(args);
        Map<String, Object> result = run(options);
        System.out.println(new ObjectMapper().writeValueAsString(result.get("measured")));
    }

    static Map<String, Object> run(LoadOptions options) throws InterruptedException {
        Properties producerConfig = producerConfig(options);
        MessagePlan plan = new MessagePlan(options);
        long total = options.warmupMessages() + options.measuredMessages();
        AtomicLong acked = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        // 예정 시각보다 늦게 보낸 정도(µs). 발생기가 목표 발행률을 냈는지 보는 지표다.
        Histogram behind = new Histogram(TimeUnit.HOURS.toMicros(1), 3);
        MeasuredWindow window;
        long windowStartMicros;
        long finishedMicros;

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerConfig);
             PublishedLog log = new PublishedLog(options.runDir().resolve("published.csv"))) {
            long startNanos = System.nanoTime();
            long startWallMicros = wallMicros();
            Pacer pacer = new Pacer(options.rate(), startNanos, System::nanoTime);
            window = new MeasuredWindow(pacer.scheduledNanos(options.warmupMessages()), options.seconds());
            for (long sequence = 0; sequence < total; sequence++) {
                MessagePlan.Message message = plan.next(sequence);
                long requestNanos = pacer.awaitSlot(sequence);
                long scheduledNanos = pacer.scheduledNanos(sequence);
                long scheduledMicros = startWallMicros + (scheduledNanos - startNanos) / 1_000;
                long requestedMicros = startWallMicros + (requestNanos - startNanos) / 1_000;
                if (message.phase().equals("measure")) {
                    behind.recordValue(Math.min(behind.getHighestTrackableValue(), (requestNanos - scheduledNanos) / 1_000));
                    window.sent(requestNanos);
                }
                ProducerRecord<String, String> record = new ProducerRecord<>(options.topic(), message.key(), message.payload());
                record.headers().add(EventHeaders.EVENT_ID, message.eventId().getBytes(StandardCharsets.UTF_8));
                record.headers().add(EventHeaders.EVENT_TYPE, BenchMessage.EVENT_TYPE.getBytes(StandardCharsets.UTF_8));
                record.headers().add(BenchMessage.SENT_AT_HEADER, Long.toString(requestedMicros).getBytes(StandardCharsets.UTF_8));
                if (!message.failure().isEmpty()) {
                    record.headers().add(FailurePlan.HEADER, message.failure().getBytes(StandardCharsets.UTF_8));
                }
                int payloadBytes = message.payload().length();
                // 확인 지연의 기준은 요청 시각(requestedMicros)과 같은 순간이다 — 요약기가 확인 시각을 요청 시각 + 확인 지연으로 되살린다.
                producer.send(record, (metadata, exception) -> {
                    long ackNanos = System.nanoTime() - requestNanos;
                    if (exception == null) {
                        acked.incrementAndGet();
                        log.add(message.eventId(), message.key(), message.phase(), message.failure(), payloadBytes,
                                scheduledMicros, requestedMicros, ackNanos, metadata.partition(), metadata.offset(), "");
                    } else {
                        failed.incrementAndGet();
                        log.add(message.eventId(), message.key(), message.phase(), message.failure(), payloadBytes,
                                scheduledMicros, requestedMicros, ackNanos, -1, -1, exception.getClass().getSimpleName());
                    }
                });
            }
            producer.flush();
            // 부하 종료 = 마지막 확인까지 받은 순간. 측정 스크립트가 결정 231의 10분 관찰 기한을 이 시각부터 잰다.
            finishedMicros = startWallMicros + (System.nanoTime() - startNanos) / 1_000;
            windowStartMicros = startWallMicros + (window.startNanos() - startNanos) / 1_000;
        }

        Map<String, Object> measuredResult = new LinkedHashMap<>();
        measuredResult.put("target_rate", options.rate());
        measuredResult.put("messages", options.measuredMessages());
        // 측정 창은 예정 시각으로 고정한다: [측정 첫 메시지의 예정 시각, + 측정 시간). 늦게 보낸 메시지는 창 밖으로 센다(PR #253 리뷰 R1).
        measuredResult.put("window_start_us", windowStartMicros);
        measuredResult.put("window_end_us", windowStartMicros + options.seconds() * 1_000_000L);
        measuredResult.put("window_seconds", options.seconds());
        measuredResult.put("sent_in_window", window.inWindow());
        measuredResult.put("sent_after_window", window.afterWindow());
        measuredResult.put("achieved_rate", window.achievedRate());
        measuredResult.put("behind_schedule_us", Map.of(
                "p50", behind.getValueAtPercentile(50), "p99", behind.getValueAtPercentile(99),
                "max", behind.getMaxValue(), "count", behind.getTotalCount()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("options", Map.of(
                "topic", options.topic(), "rate", options.rate(), "warmup_seconds", options.warmupSeconds(),
                "seconds", options.seconds(), "payload_bytes", options.payloadBytes(), "keys", options.keys(),
                "transient_ratio", options.transientRatio(), "transient_failures", options.transientFailures(),
                "permanent_ratio", options.permanentRatio(), "seed", options.seed()));
        result.put("producer", stringMap(producerConfig));
        result.put("sent", total);
        result.put("acked", acked.get());
        result.put("ack_failed", failed.get());
        result.put("measured", measuredResult);
        result.put("finished_us", finishedMicros);
        write(options, result);
        return result;
    }

    static Properties producerConfig(LoadOptions options) {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, options.bootstrap());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        // 운영 앱 application.yml 과 같은 값.
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.RETRIES_CONFIG, "3");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        config.putAll(options.producerOverrides());
        return config;
    }

    private static Map<String, String> stringMap(Properties properties) {
        Map<String, String> values = new LinkedHashMap<>();
        properties.stringPropertyNames().stream().sorted().forEach(name -> values.put(name, properties.getProperty(name)));
        return values;
    }

    private static void write(LoadOptions options, Map<String, Object> result) {
        try {
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValue(options.runDir().resolve("generator.json").toFile(), result);
        } catch (IOException e) {
            throw new UncheckedIOException("generator.json 을 쓸 수 없다", e);
        }
    }

    private static long wallMicros() {
        return ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now());
    }
}
