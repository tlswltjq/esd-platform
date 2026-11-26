package com.stove.bench.messaging.load;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.UUID;

/**
 * 몇 번째 메시지를 어떻게 보낼지 정한다(E13 벤치마크 계약 1). 같은 씨앗이면 같은 키·실패 배치가 나온다 — 회차를 다시 돌려도 같은 부하다.
 *
 * <p>키는 {@code keys}개를 순서대로 돌려 쓴다(고른 분포). 실패는 측정 구간에만 넣고 예열은 깨끗하게 둔다.
 */
public final class MessagePlan {

    /** {@code {"seq":N,"pad":""}}의 최소 길이보다 넉넉한 값. */
    static final int MIN_PAYLOAD_BYTES = 64;

    private final LoadOptions options;
    private final Random random;

    public MessagePlan(LoadOptions options) {
        this.options = options;
        this.random = new Random(options.seed());
    }

    /** 0부터 차례로 부른다. 실패 배치가 순서에 따라 정해지므로 건너뛰거나 거꾸로 부르지 않는다. */
    public Message next(long sequence) {
        boolean warmup = sequence < options.warmupMessages();
        String key = "k-%05d".formatted(sequence % options.keys());
        String failure = "";
        // 메시지마다 난수를 하나씩 꺼낸다(예열 포함). 같은 인자면 같은 배치가 나온다.
        double draw = random.nextDouble();
        if (!warmup) {
            if (draw < options.transientRatio()) {
                failure = "transient:" + options.transientFailures();
            } else if (draw < options.transientRatio() + options.permanentRatio()) {
                failure = "permanent";
            }
        }
        return new Message(UUID.randomUUID().toString(), key, warmup ? "warmup" : "measure", failure,
                payload(sequence, options.payloadBytes()));
    }

    /** UTF-8로 정확히 {@code bytes}바이트인 JSON 본문. ASCII 패딩이라 문자 수 = 바이트 수다. */
    static String payload(long sequence, int bytes) {
        String head = "{\"seq\":" + sequence + ",\"pad\":\"";
        String tail = "\"}";
        int padding = bytes - head.length() - tail.length();
        if (padding < 0) {
            throw new IllegalArgumentException("본문 크기가 너무 작다: " + bytes);
        }
        String body = head + "x".repeat(padding) + tail;
        if (body.getBytes(StandardCharsets.UTF_8).length != bytes) {
            throw new IllegalStateException("본문 크기 계산이 틀렸다");
        }
        return body;
    }

    /**
     * @param phase {@code warmup} 또는 {@code measure}.
     * @param failure {@code x-bench-fail} 값. 없으면 빈 문자열.
     */
    public record Message(String eventId, String key, String phase, String failure, String payload) {
    }
}
