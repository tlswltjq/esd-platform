package com.stove.bench.messaging.report;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import org.HdrHistogram.Histogram;

/**
 * 지연 분포 하나(µs). E13 지표 정의대로 HdrHistogram(유효숫자 3자리, 1µs ~ 1시간)에 모으고, 요약값과 함께 원본 히스토그램을
 * 압축·Base64로 남긴다 — SLO가 정해지면 다른 기준으로 다시 계산할 수 있게(결정 231·233).
 *
 * <p>음수(앞 시각이 뒤 시각보다 늦음)는 분포에 넣지 않고 따로 센다. 두 시각을 다른 프로세스가 찍어 생기는 측정 결함이다.
 * 1시간을 넘는 값도 따로 센다.
 */
public final class Latency {

    static final long LOWEST_US = 1;
    static final long HIGHEST_US = TimeUnit.HOURS.toMicros(1);
    static final int DIGITS = 3;
    /** 그래프용 백분위 점. 원본 히스토그램이 있으므로 여기에 없는 점도 다시 낼 수 있다. */
    static final double[] CURVE = {0, 10, 25, 50, 75, 90, 95, 99, 99.5, 99.9, 99.99, 100};

    private final Histogram histogram = new Histogram(LOWEST_US, HIGHEST_US, DIGITS);
    private long negative;
    private long overflow;

    public void record(long micros) {
        if (micros < 0) {
            negative++;
        } else if (micros > HIGHEST_US) {
            overflow++;
        } else {
            histogram.recordValue(Math.max(LOWEST_US, micros));
        }
    }

    public long count() {
        return histogram.getTotalCount();
    }

    public long percentile(double p) {
        return histogram.getValueAtPercentile(p);
    }

    /** 요약. 표본이 없으면 값 대신 {@code null}을 둔다(0과 구분). */
    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        long count = histogram.getTotalCount();
        summary.put("count", count);
        summary.put("negative", negative);
        summary.put("overflow", overflow);
        summary.put("p50", count == 0 ? null : histogram.getValueAtPercentile(50));
        summary.put("p95", count == 0 ? null : histogram.getValueAtPercentile(95));
        summary.put("p99", count == 0 ? null : histogram.getValueAtPercentile(99));
        summary.put("p999", count == 0 ? null : histogram.getValueAtPercentile(99.9));
        summary.put("mean", count == 0 ? null : histogram.getMean());
        summary.put("max", count == 0 ? null : histogram.getMaxValue());
        List<List<Object>> curve = new ArrayList<>();
        if (count > 0) {
            for (double p : CURVE) {
                curve.add(List.of(p, histogram.getValueAtPercentile(p)));
            }
        }
        summary.put("curve", curve);
        summary.put("histogram", Map.of(
                "encoding", "HdrHistogram V2 compressed, base64",
                "unit", "us", "lowest", LOWEST_US, "highest", HIGHEST_US, "significant_digits", DIGITS,
                "data", encode()));
        return summary;
    }

    private String encode() {
        ByteBuffer buffer = ByteBuffer.allocate(histogram.getNeededByteBufferCapacity());
        int length = histogram.encodeIntoCompressedByteBuffer(buffer);
        byte[] bytes = new byte[length];
        buffer.flip();
        buffer.get(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** 남긴 원본을 되살린다(재판정·시험용). */
    public static Histogram decode(String base64) {
        try {
            return Histogram.decodeFromCompressedByteBuffer(ByteBuffer.wrap(Base64.getDecoder().decode(base64)), LOWEST_US);
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("히스토그램을 읽을 수 없다", e);
        }
    }
}
