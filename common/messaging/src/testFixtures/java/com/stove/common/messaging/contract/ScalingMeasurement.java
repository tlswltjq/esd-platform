package com.stove.common.messaging.contract;

import com.stove.common.contract.EventHeaders;
import com.stove.common.event.EventHandler;
import com.stove.common.event.InboundMessage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * S4 소비 인스턴스 확장(결정 210 특성 4, 결정 218)의 한 회차 기록.
 *
 * <p>인스턴스마다 {@link Instance} 핸들러 하나를 구독에 붙인다. 핸들러는 처리 전에 공유 {@link #release} 문이 열리기를
 * 기다리고(그동안 메시지를 쌓는다), 인스턴스 안에서 한 번에 한 건만 {@code cost} 동안 처리한다(인스턴스별 처리 한도 —
 * 한 인스턴스가 분할마다 스레드를 따로 쓰므로 한도가 없으면 인스턴스 수가 처리량을 바꾸지 않는다).
 */
final class ScalingMeasurement {

    /** 처리 한 건의 기록. {@code nanos} 는 처리를 마친 시각({@link System#nanoTime()}). */
    record Processed(String eventId, String key, int instance, long nanos) {
    }

    private final Duration cost;
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<Processed> processed = Collections.synchronizedList(new ArrayList<>());
    private volatile long releasedAt;

    ScalingMeasurement(Duration cost) {
        this.cost = cost;
    }

    EventHandler instance(int number) {
        return new Instance(number);
    }

    /** 쌓아 둔 메시지를 처리하기 시작한다. 이 시각부터 비우는 시간을 잰다. */
    void release() {
        releasedAt = System.nanoTime();
        release.countDown();
    }

    long releasedAt() {
        return releasedAt;
    }

    int distinct() {
        synchronized (processed) {
            Set<String> ids = new HashSet<>();
            for (Processed one : processed) {
                ids.add(one.eventId());
            }
            return ids.size();
        }
    }

    List<Processed> snapshot() {
        synchronized (processed) {
            return new ArrayList<>(processed);
        }
    }

    /** 각 메시지가 처음 처리된 시각 가운데 가장 늦은 것. 다 비운 시각이다. */
    static long drainedAt(List<Processed> records) {
        Map<String, Long> first = new HashMap<>();
        for (Processed one : records) {
            first.merge(one.eventId(), one.nanos(), Math::min);
        }
        return first.values().stream().mapToLong(Long::longValue).max().orElse(0);
    }

    /** 인스턴스별 처리 건수(재전달 포함). 인스턴스 번호 순서. */
    static int[] perInstance(List<Processed> records, int instances) {
        int[] counts = new int[instances];
        for (Processed one : records) {
            counts[one.instance()]++;
        }
        return counts;
    }

    /**
     * 인계 시간: {@code closedAt} 전에 {@code closed} 인스턴스가 처리하던 키마다, 닫힌 뒤 다른 인스턴스가 그 키를 처음
     * 처리하기까지 걸린 시간(ms). 닫힌 뒤 다시 처리되지 않은 키(이미 다 처리됨)는 뺀다.
     */
    static List<Double> handoverMillis(List<Processed> records, int closed, long closedAt) {
        Set<String> owned = new HashSet<>();
        Map<String, Long> resumed = new HashMap<>();
        for (Processed one : records) {
            if (one.instance() == closed && one.nanos() <= closedAt) {
                owned.add(one.key());
            }
        }
        for (Processed one : records) {
            if (one.instance() != closed && one.nanos() > closedAt && owned.contains(one.key())) {
                resumed.merge(one.key(), one.nanos(), Math::min);
            }
        }
        List<Double> gaps = new ArrayList<>();
        for (long at : resumed.values()) {
            gaps.add((at - closedAt) / 1_000_000.0);
        }
        Collections.sort(gaps);
        return gaps;
    }

    static String json(int[] values) {
        StringJoiner joined = new StringJoiner(",", "[", "]");
        for (int value : values) {
            joined.add(Integer.toString(value));
        }
        return joined.toString();
    }

    static String json(List<Double> values) {
        StringJoiner joined = new StringJoiner(",", "[", "]");
        for (double value : values) {
            joined.add(String.format(Locale.ROOT, "%.1f", value));
        }
        return joined.toString();
    }

    private final class Instance implements EventHandler {
        private final int number;
        private final ReentrantLock oneAtATime = new ReentrantLock(true);

        private Instance(int number) {
            this.number = number;
        }

        @Override
        public void receive(InboundMessage message) {
            try {
                if (!release.await(10, TimeUnit.MINUTES)) {
                    throw new IllegalStateException("S4 측정 문이 열리지 않았다");
                }
                oneAtATime.lock();
                try {
                    Thread.sleep(cost.toMillis());
                    processed.add(new Processed(message.header(EventHeaders.EVENT_ID), message.key(), number, System.nanoTime()));
                } finally {
                    oneAtATime.unlock();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
    }
}
