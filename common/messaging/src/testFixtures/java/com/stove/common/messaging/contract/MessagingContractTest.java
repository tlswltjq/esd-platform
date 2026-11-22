package com.stove.common.messaging.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.contract.EventHeaders;
import com.stove.common.event.EventHandler;
import com.stove.common.event.InboundMessage;
import com.stove.common.event.KeyQuarantine;
import com.stove.common.event.OutboundMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 메시징 adapter 공통 계약(결정 202·206·209). 네 adapter(Kafka·RabbitMQ·Redis Streams·브로커 없음)의 통합 테스트가
 * 이 클래스를 상속해 같은 단언을 통과해야 비교 측정을 한다(결정 203).
 *
 * <p>adapter 가 구현할 것은 둘이다: {@link #subscribe} (그룹·토픽 구독 시작)와 {@link #publish} (발행).
 * 격리는 코어의 {@link KeyQuarantine} (소비 모듈 DB)를 그대로 쓴다 — 기술과 무관하게 같은 격리 경로를 탄다.
 *
 * <p>계약:
 * <ol>
 *   <li>같은 키는 발행 순서대로, 한 번씩, 한 번에 하나씩 처리된다. 앞 처리를 붙잡아 둔 동안 뒤 메시지는 어느 인스턴스에서도 시작되지 않는다.</li>
 *   <li>같은 그룹의 인스턴스가 둘이어도 한 메시지는 한 인스턴스만 처리하고, 같은 키를 두 인스턴스가 동시에 처리하지 않는다.</li>
 *   <li>다른 그룹은 각자 모든 메시지를 받는다(팬아웃).</li>
 *   <li>처리 실패는 제자리에서 재시도(최초 1회 + 재시도 {@value #RETRIES}회)한 뒤 격리하고 그 키만 멈춘다.
 *       멈춘 키의 뒤 메시지는 처리하지 않고 격리 뒤에 쌓는다. 다른 키는 계속 흐른다.</li>
 *   <li>재시도 제외 예외는 첫 실패에 격리한다.</li>
 *   <li>격리 기록이 실패하면 그 메시지를 붙잡아, 같은 키의 뒤 메시지가 추월하지 못한다.</li>
 *   <li>재시도 도중 성공하면 격리하지 않고, 같은 키의 뒤 메시지는 그 성공 뒤에 처리된다.</li>
 * </ol>
 *
 * <p>건수 단언은 목표 건수에 닿은 뒤 {@link #quietPeriod()} 동안 더 오는 것이 없는지 보고 한다 — 닿자마자 세면 직후의 중복 전달을 놓친다.
 */
public abstract class MessagingContractTest {

    /** 최초 배달 뒤 재시도 횟수. Kafka {@code ConsumerRetryPolicy.MAX_RETRIES}·코어 {@code InPlaceDelivery.RETRY_DELAYS} 와 같다. */
    protected static final int RETRIES = 3;

    /**
     * 순서·동시성 시험에서 메시지마다 처리 중에 머무는 시간. 같은 키의 다음 메시지가 발행 간격(한 건씩 확인을 기다림)보다
     * 이 시간 안에 도착해야 겹쳐 처리하는 adapter 가 드러난다 — 20ms 로는 비동기로 넘기는 변이도 통과했다(PR #219 R1 변이 확인).
     */
    private static final Duration HOLD = Duration.ofMillis(150);

    @Autowired
    protected KeyQuarantine quarantine;

    @Autowired
    protected JdbcTemplate jdbc;

    protected String group;
    protected String topic;

    private final List<AutoCloseable> started = new ArrayList<>();

    /**
     * 구독을 시작한다. 같은 그룹·토픽으로 여러 번 부르면 같은 그룹의 인스턴스가 늘어난다.
     *
     * @param storage    격리처. 보통 {@link #quarantine}, 격리 기록 실패를 흉내 낼 때는 감싼 것
     * @param noRetryFor 재시도해도 결과가 같은 예외
     */
    protected abstract AutoCloseable subscribe(String group, String topic, KeyQuarantine storage, EventHandler handler,
                                               List<Class<? extends RuntimeException>> noRetryFor);

    /** 발행한다. 돌아오면 그 adapter 기준으로 메시지가 내구성 있게 받아들여졌다(발행 포트 계약). */
    protected abstract void publish(OutboundMessage message);

    /** 테스트 토픽을 준비한다(예: Kafka 는 파티션 여러 개로 만든다). */
    protected void prepareTopic(String topic) {
    }

    /** 모든 구독이 메시지를 받을 준비가 될 때까지 기다린다(예: Kafka 는 파티션 배정 완료). 발행 전에 부른다. */
    protected void awaitSubscriptionsReady() {
    }

    /** 목표 건수에 닿은 뒤 더 오는 것(중복 전달)이 없는지 지켜보는 시간. */
    protected Duration quietPeriod() {
        return Duration.ofSeconds(2);
    }

    /** 한 단언을 기다리는 최대 시간. 재시도 간격이 긴 adapter(Kafka 1·2·4초)는 늘린다. */
    protected Duration timeout() {
        return Duration.ofSeconds(30);
    }

    @BeforeEach
    void prepareContract() {
        group = "contract-" + UUID.randomUUID();
        topic = "contract." + UUID.randomUUID().toString().substring(0, 8);
        prepareTopic(topic);
    }

    @AfterEach
    void stopContractSubscriptions() throws Exception {
        // 하나가 실패해도 나머지를 모두 닫는다. 살아남은 소비자가 다음 테스트의 공유 표·브로커에 끼어들지 않게.
        Exception failure = null;
        for (AutoCloseable subscription : started) {
            try {
                subscription.close();
            } catch (Exception closing) {
                if (failure == null) {
                    failure = closing;
                } else {
                    failure.addSuppressed(closing);
                }
            }
        }
        started.clear();
        try {
            closeAdapter();
        } catch (Exception closing) {
            if (failure == null) {
                failure = closing;
            } else {
                failure.addSuppressed(closing);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * 구독을 모두 멈춘 뒤 adapter 자원(연결 등)을 닫는다. 하위 클래스의 {@code @AfterEach} 는 이 클래스의 것보다 먼저 돌아
     * 구독이 살아 있는 동안 연결을 닫게 되므로, 정리는 여기서 한다.
     */
    protected void closeAdapter() throws Exception {
    }

    /** 목록을 adapter 들이 받는 배열로 바꾼다. 제네릭 배열은 만들 수 없어 한 번 좁힌다(원소 타입은 목록이 보장한다). */
    @SuppressWarnings("unchecked")
    protected static Class<? extends RuntimeException>[] asArray(List<Class<? extends RuntimeException>> noRetryFor) {
        return (Class<? extends RuntimeException>[]) noRetryFor.toArray(new Class<?>[0]);
    }

    protected final void start(String inGroup, KeyQuarantine storage, EventHandler handler,
                               List<Class<? extends RuntimeException>> noRetryFor) {
        started.add(subscribe(inGroup, topic, storage, handler, noRetryFor));
    }

    protected final void start(String inGroup, EventHandler handler) {
        start(inGroup, quarantine, handler, List.of());
    }

    protected final String send(String key) {
        String eventId = UUID.randomUUID().toString();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(EventHeaders.EVENT_ID, eventId);
        headers.put(EventHeaders.EVENT_TYPE, "ContractHappened");
        headers.put(EventHeaders.OCCURRED_AT, Instant.now().toString());
        publish(new OutboundMessage(topic, key, "{\"key\":\"" + key + "\"}", headers));
        return eventId;
    }

    protected final void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout().toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("기다렸지만 이뤄지지 않았다: " + what);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
    }

    /** 목표에 닿은 뒤 {@link #quietPeriod()} 동안 기다린다. 그 뒤에 정확한 건수를 단언한다. */
    protected final void settle() {
        try {
            Thread.sleep(quietPeriod().toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private int quarantinedCount(String inGroup) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM consumer_quarantine WHERE consumer_group = ?",
                Integer.class, inGroup);
        return count == null ? 0 : count;
    }

    private String keyStoppedEventId(String inGroup) {
        return jdbc.queryForObject(
                "SELECT event_id FROM consumer_quarantine WHERE consumer_group = ? AND failure = 'KEY_STOPPED'",
                String.class, inGroup);
    }

    @Test
    void 같은_키는_발행_순서대로_한_번씩_한_번에_하나씩_처리된다() {
        PerKeyConcurrency concurrency = new PerKeyConcurrency();
        Recording handler = new Recording(concurrency, HOLD);
        start(group, handler);
        awaitSubscriptionsReady();
        Map<String, List<String>> sentByKey = new LinkedHashMap<>();
        for (int round = 0; round < 5; round++) {
            for (int k = 0; k < 6; k++) {
                String key = "K-" + k;
                sentByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(send(key));
            }
        }

        await("30건 수신", () -> handler.ids().size() >= 30);
        settle();

        assertThat(handler.ids()).as("목표 뒤 관찰 구간에도 더 오지 않는다(중복 없음)").hasSize(30);
        for (Map.Entry<String, List<String>> sent : sentByKey.entrySet()) {
            assertThat(handler.idsOf(sent.getKey())).as("키 %s 는 보낸 순서대로 한 번씩", sent.getKey())
                    .containsExactlyElementsOf(sent.getValue());
        }
        assertThat(concurrency.max()).as("같은 키를 동시에 처리한 최대 수").isEqualTo(1);
        assertThat(handler.firstPayload().replace(" ", "")).startsWith("{\"key\":");
    }

    @Test
    void 같은_그룹_인스턴스_둘은_한_메시지를_함께_처리하지_않고_같은_키를_동시에_처리하지_않는다() {
        PerKeyConcurrency concurrency = new PerKeyConcurrency();
        Recording first = new Recording(concurrency, HOLD);
        Recording second = new Recording(concurrency, HOLD);
        start(group, first);
        start(group, second);
        awaitSubscriptionsReady();
        List<String> sent = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            sent.add(send("K-" + (i % 4)));
        }

        await("20건 수신", () -> first.ids().size() + second.ids().size() >= 20);
        settle();

        List<String> all = new ArrayList<>(first.ids());
        all.addAll(second.ids());
        assertThat(all).as("같은 그룹에서는 한 메시지를 한 인스턴스만 처리한다").containsExactlyInAnyOrderElementsOf(sent);
        assertThat(concurrency.max()).as("두 인스턴스를 합쳐 같은 키를 동시에 처리한 최대 수").isEqualTo(1);
    }

    /**
     * 한계(PR #219 R9, 부분 반영): 뒤 메시지가 처리 가능한 단계에 들어간 시점은 브로커마다 달라(Kafka poll·RabbitMQ prefetch·
     * Redis XREADGROUP·direct 커서) 공통으로 관찰하지 않는다. 관찰 구간({@link #settle()}) 안에 뒤 메시지가 전달되지 않는
     * adapter라면 동시 처리 결함이 있어도 이 계약은 통과할 수 있다. adapter에 테스트 전용 hook을 두지 않으려고 이 한계를 받아들인다.
     * Kafka 수신부가 처리를 별도 스레드로 넘기는 변이는 이 계약이 {@code expected: 0 but was: 1}로 잡는다.
     */
    @Test
    void 같은_키의_앞_처리가_끝나기_전에는_어느_인스턴스도_뒤_메시지를_시작하지_않는다() throws InterruptedException {
        String key = "K-" + UUID.randomUUID();
        Gate gate = new Gate(key);
        PerKeyConcurrency concurrency = new PerKeyConcurrency();
        Recording first = new Recording(concurrency, Duration.ZERO);
        Recording second = new Recording(concurrency, Duration.ZERO);
        first.holdFirstMessageOf(gate);
        second.holdFirstMessageOf(gate);
        start(group, first);
        start(group, second);
        awaitSubscriptionsReady();

        String held = send(key);
        String behind;
        try {
            assertThat(gate.entered.await(timeout().toSeconds(), TimeUnit.SECONDS)).as("앞 메시지 처리가 시작됐다").isTrue();
            behind = send(key);
            settle();

            assertThat(first.attempts(behind) + second.attempts(behind))
                    .as("앞 처리가 붙잡혀 있는 동안 같은 키의 뒤 메시지는 어느 인스턴스에서도 시작되지 않는다").isZero();
        } finally {
            gate.open();
        }
        await("두 건 수신", () -> first.ids().size() + second.ids().size() >= 2);
        settle();

        List<String> all = new ArrayList<>(first.ids());
        all.addAll(second.ids());
        assertThat(all).containsExactlyInAnyOrder(held, behind);
        assertThat(concurrency.max()).isEqualTo(1);
    }

    @Test
    void 다른_그룹은_각자_모든_메시지를_받는다() {
        Recording mine = new Recording();
        Recording theirs = new Recording();
        String otherGroup = group + "-other";
        start(group, mine);
        start(otherGroup, theirs);
        awaitSubscriptionsReady();
        List<String> sent = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sent.add(send("K-" + (i % 3)));
        }

        await("두 그룹 모두 10건", () -> mine.ids().size() >= 10 && theirs.ids().size() >= 10);
        settle();

        assertThat(mine.ids()).containsExactlyInAnyOrderElementsOf(sent);
        assertThat(theirs.ids()).containsExactlyInAnyOrderElementsOf(sent);
    }

    @Test
    void 실패는_제자리_재시도_뒤_격리하고_그_키만_멈춘다() {
        String broken = "K-broken-" + UUID.randomUUID();
        String healthy = "K-healthy-" + UUID.randomUUID();
        Recording handler = new Recording();
        handler.failKey = broken;
        start(group, handler);
        awaitSubscriptionsReady();

        String failing = send(broken);
        String behind = send(broken);
        String other = send(healthy);

        await("격리 2건", () -> quarantinedCount(group) >= 2);
        await("다른 키 수신", () -> handler.ids().contains(other));
        settle();

        assertThat(handler.attempts(failing)).as("최초 1회 + 재시도 %d회", RETRIES).isEqualTo(1 + RETRIES);
        assertThat(handler.attempts(behind)).as("멈춘 키의 뒤 메시지는 처리하지 않고 보관").isZero();
        assertThat(quarantine.stopped(group, topic, broken)).isTrue();
        assertThat(quarantine.stopped(group, topic, healthy)).isFalse();
        assertThat(keyStoppedEventId(group)).isEqualTo(behind);
    }

    @Test
    void 재시도_제외_예외는_첫_실패에_격리한다() {
        String key = "K-" + UUID.randomUUID();
        Recording handler = new Recording();
        handler.rejectKey = key;
        start(group, quarantine, handler, List.of(Rejected.class));
        awaitSubscriptionsReady();

        String rejected = send(key);

        await("격리", () -> quarantine.stopped(group, topic, key));
        settle();
        assertThat(handler.attempts(rejected)).isEqualTo(1);
    }

    @Test
    void 격리_기록이_실패하면_메시지를_붙잡아_같은_키_뒤_메시지가_추월하지_못한다() {
        String key = "K-" + UUID.randomUUID();
        Recording handler = new Recording();
        handler.failKey = key;
        FlakyQuarantine storage = new FlakyQuarantine(quarantine, 2);
        start(group, storage, handler, List.of());
        awaitSubscriptionsReady();

        String first = send(key);
        String second = send(key);

        await("두 건 모두 보관", () -> quarantinedCount(group) >= 2);
        settle();
        assertThat(storage.failures()).as("앞 메시지의 보관이 두 번 실패했다").isEqualTo(2);
        assertThat(handler.attempts(second)).as("앞 메시지가 붙잡혀 있는 동안 뒤 메시지는 처리되지 않고, 키 정지 뒤 보관된다")
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT event_id FROM consumer_quarantine WHERE consumer_group = ? AND failure <> 'KEY_STOPPED'",
                String.class, group)).isEqualTo(first);
        assertThat(keyStoppedEventId(group)).isEqualTo(second);
    }

    @Test
    void 재시도_도중_성공하면_격리하지_않고_같은_키_뒤_메시지는_그_성공_뒤에_처리된다() {
        String key = "K-" + UUID.randomUUID();
        Recording handler = new Recording(null, Duration.ZERO);
        handler.failFirstMessageOf(key, 2);
        start(group, handler);
        awaitSubscriptionsReady();

        String first = send(key);
        String behind = send(key);

        await("두 건 수신", () -> handler.ids().size() >= 2);
        settle();

        assertThat(handler.ids()).as("앞 메시지가 성공한 뒤 뒤 메시지").containsExactly(first, behind);
        assertThat(handler.attempts(first)).as("두 번 실패 뒤 세 번째에 성공").isEqualTo(3);
        assertThat(handler.firstAttemptIndex(behind)).as("뒤 메시지는 앞 메시지의 마지막 시도 뒤에 처음 시도된다")
                .isGreaterThan(handler.lastAttemptIndex(first));
        assertThat(quarantinedCount(group)).as("재시도 안에서 성공하면 격리하지 않는다").isZero();
        assertThat(quarantine.stopped(group, topic, key)).isFalse();
    }

    /** 처음 몇 번의 보관 기록을 실패시킨다(격리 DB 가 잠깐 안 되는 상황). */
    protected static final class FlakyQuarantine implements KeyQuarantine {
        private final KeyQuarantine delegate;
        private final AtomicInteger remainingFailures;
        private final AtomicInteger failures = new AtomicInteger();

        public FlakyQuarantine(KeyQuarantine delegate, int failures) {
            this.delegate = delegate;
            this.remainingFailures = new AtomicInteger(failures);
        }

        public int failures() {
            return failures.get();
        }

        @Override
        public boolean stopped(String consumerGroup, String topic, String key) {
            return delegate.stopped(consumerGroup, topic, key);
        }

        @Override
        public void quarantine(String consumerGroup, InboundMessage message, Throwable failure) {
            if (remainingFailures.getAndDecrement() > 0) {
                failures.incrementAndGet();
                throw new IllegalStateException("격리 DB 잠깐 안 됨");
            }
            delegate.quarantine(consumerGroup, message, failure);
        }
    }

    /** 재시도 제외로 선언하는 예외. */
    protected static final class Rejected extends RuntimeException {
        public Rejected() {
            super("rejected");
        }
    }

    /** 같은 키의 첫 메시지 처리를 붙잡아 두는 문. 붙잡힌 동안 같은 키의 다른 메시지가 시작되는지 본다(PR #219 R1). */
    protected static final class Gate {
        private final String key;
        private final AtomicReference<String> held = new AtomicReference<>();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        Gate(String key) {
            this.key = key;
        }

        boolean claim(String eventId) {
            // 붙잡은 메시지는 재전달·재시도돼도 문이 열릴 때까지 다시 붙잡는다(PR #219 R7). 열린 뒤에는 await가 바로 끝난다.
            return held.compareAndSet(null, eventId) || eventId.equals(held.get());
        }

        void open() {
            release.countDown();
        }
    }

    /** 같은 키를 동시에 처리 중인 수와 그 최댓값. 여러 인스턴스의 핸들러가 하나를 나눠 쓴다. */
    protected static final class PerKeyConcurrency {
        private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
        private final AtomicInteger max = new AtomicInteger();

        void enter(String key) {
            int now = inFlight.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
            max.accumulateAndGet(now, Math::max);
        }

        void exit(String key) {
            inFlight.get(key).decrementAndGet();
        }

        public int max() {
            return max.get();
        }
    }

    /**
     * 받은 메시지와 시도를 기록한다. {@code failKey} 는 늘 실패, {@code rejectKey} 는 재시도 제외 예외,
     * {@link #failFirstMessageOf} 는 그 키의 첫 메시지만 정한 횟수 실패한 뒤 성공한다.
     * {@code hold} 만큼 처리 중에 머물러, 같은 키를 겹쳐 처리하는 adapter 가 있으면 {@link PerKeyConcurrency} 에 드러나게 한다.
     */
    protected static final class Recording implements EventHandler {
        private final List<InboundMessage> received = Collections.synchronizedList(new ArrayList<>());
        private final List<String> tried = Collections.synchronizedList(new ArrayList<>());
        private final PerKeyConcurrency concurrency;
        private final Duration hold;
        private volatile String failKey;
        private volatile String rejectKey;
        private volatile Gate gate;
        private volatile String flakyKey;
        private volatile String flakyEventId;
        private final AtomicInteger flakyFailuresLeft = new AtomicInteger();

        public Recording() {
            this(null, Duration.ZERO);
        }

        public Recording(PerKeyConcurrency concurrency, Duration hold) {
            this.concurrency = concurrency;
            this.hold = hold;
        }

        /** 그 키의 첫 메시지 처리를 {@code gate} 가 열릴 때까지 붙잡는다. 여러 인스턴스의 핸들러가 같은 문을 나눠 쓴다. */
        void holdFirstMessageOf(Gate shared) {
            gate = shared;
        }

        void failFirstMessageOf(String key, int failures) {
            flakyKey = key;
            flakyFailuresLeft.set(failures);
        }

        List<String> ids() {
            synchronized (received) {
                return received.stream().map(message -> message.header(EventHeaders.EVENT_ID)).toList();
            }
        }

        List<String> idsOf(String key) {
            synchronized (received) {
                return received.stream().filter(message -> key.equals(message.key()))
                        .map(message -> message.header(EventHeaders.EVENT_ID)).toList();
            }
        }

        String firstPayload() {
            synchronized (received) {
                return received.get(0).payload();
            }
        }

        int attempts(String eventId) {
            synchronized (tried) {
                return (int) tried.stream().filter(eventId::equals).count();
            }
        }

        int firstAttemptIndex(String eventId) {
            synchronized (tried) {
                return tried.indexOf(eventId);
            }
        }

        int lastAttemptIndex(String eventId) {
            synchronized (tried) {
                return tried.lastIndexOf(eventId);
            }
        }

        @Override
        public void receive(InboundMessage message) {
            String eventId = message.header(EventHeaders.EVENT_ID);
            tried.add(eventId);
            if (concurrency != null) {
                concurrency.enter(message.key());
            }
            try {
                Gate shared = gate;
                if (shared != null && shared.key.equals(message.key()) && shared.claim(eventId)) {
                    shared.entered.countDown();
                    if (!shared.release.await(2, TimeUnit.MINUTES)) {
                        throw new IllegalStateException("문이 열리지 않았다");
                    }
                }
                if (!hold.isZero()) {
                    Thread.sleep(hold.toMillis());
                }
                if (message.key().equals(failKey)) {
                    throw new IllegalStateException("boom");
                }
                if (message.key().equals(rejectKey)) {
                    throw new Rejected();
                }
                if (message.key().equals(flakyKey)) {
                    if (flakyEventId == null) {
                        flakyEventId = eventId;
                    }
                    if (eventId.equals(flakyEventId) && flakyFailuresLeft.getAndDecrement() > 0) {
                        throw new IllegalStateException("잠깐 실패");
                    }
                }
                received.add(message);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            } finally {
                if (concurrency != null) {
                    concurrency.exit(message.key());
                }
            }
        }
    }
}
