package com.stove.bench.messaging.load;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 부하 발생기 인자({@code --이름=값}). 기본값은 결정 230의 층 2 기준 조건(1KB·키 600개)이다. 발행률·시간은 회차마다 준다.
 *
 * @param producerOverrides {@code --producer.<키>=<값>}. 운영과 다른 producer 설정 계열만 쓴다(결정 230: 기존 설정과 섞지 않는다).
 */
public record LoadOptions(String bootstrap, String topic, Path runDir, double rate, int warmupSeconds, int seconds,
                          int payloadBytes, int keys, double transientRatio, int transientFailures,
                          double permanentRatio, long seed, Map<String, String> producerOverrides) {

    public LoadOptions {
        if (bootstrap == null || bootstrap.isBlank() || topic == null || topic.isBlank() || runDir == null) {
            throw new IllegalArgumentException("--bootstrap·--topic·--run-dir 는 필수다");
        }
        if (!(rate > 0)) {
            throw new IllegalArgumentException("--rate 는 0보다 커야 한다: " + rate);
        }
        if (warmupSeconds < 0 || seconds <= 0) {
            throw new IllegalArgumentException("--warmup 은 0 이상, --seconds 는 1 이상이다");
        }
        if (payloadBytes < MessagePlan.MIN_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("--size 는 " + MessagePlan.MIN_PAYLOAD_BYTES + " 바이트 이상이다: " + payloadBytes);
        }
        if (keys <= 0) {
            throw new IllegalArgumentException("--keys 는 1 이상이다: " + keys);
        }
        if (transientRatio < 0 || permanentRatio < 0 || transientRatio + permanentRatio > 1) {
            throw new IllegalArgumentException("실패 비율은 0 이상이고 합이 1 이하다");
        }
        if (transientRatio > 0 && (transientFailures < 1 || transientFailures > 3)) {
            throw new IllegalArgumentException("--transient-failures 는 1~3 이다(제자리 재시도 총 4회): " + transientFailures);
        }
        producerOverrides = Map.copyOf(producerOverrides);
    }

    public static LoadOptions parse(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> producer = new LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw new IllegalArgumentException("인자는 --이름=값 형식이다: " + arg);
            }
            String name = arg.substring(2, arg.indexOf('='));
            String value = arg.substring(arg.indexOf('=') + 1);
            if (name.startsWith("producer.")) {
                producer.put(name.substring("producer.".length()), value);
            } else if (values.put(name, value) != null) {
                throw new IllegalArgumentException("같은 인자를 두 번 줬다: " + name);
            }
        }
        LoadOptions options = new LoadOptions(
                values.remove("bootstrap"),
                values.getOrDefault("topic", "bench.messages.v1"),
                values.containsKey("run-dir") ? Path.of(values.get("run-dir")) : null,
                Double.parseDouble(required(values, "rate")),
                Integer.parseInt(values.getOrDefault("warmup", "0")),
                Integer.parseInt(required(values, "seconds")),
                Integer.parseInt(values.getOrDefault("size", "1024")),
                Integer.parseInt(values.getOrDefault("keys", "600")),
                Double.parseDouble(values.getOrDefault("transient-ratio", "0")),
                Integer.parseInt(values.getOrDefault("transient-failures", "1")),
                Double.parseDouble(values.getOrDefault("permanent-ratio", "0")),
                Long.parseLong(values.getOrDefault("seed", "230")),
                producer);
        for (String known : new String[] {"topic", "run-dir", "rate", "warmup", "seconds", "size", "keys",
                "transient-ratio", "transient-failures", "permanent-ratio", "seed"}) {
            values.remove(known);
        }
        if (!values.isEmpty()) {
            throw new IllegalArgumentException("모르는 인자: " + values.keySet());
        }
        return options;
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null) {
            throw new IllegalArgumentException("--" + name + " 는 필수다");
        }
        return value;
    }

    /** 예열 + 측정 동안 보낼 메시지 수. 발행률 × 시간을 내림한다. */
    public long warmupMessages() {
        return (long) Math.floor(rate * warmupSeconds);
    }

    public long measuredMessages() {
        return (long) Math.floor(rate * seconds);
    }
}
