package com.stove.common.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.event.MessagePublisher;
import com.stove.common.messaging.config.OutboxProperties;
import com.stove.common.testcontainers.InfraContainers;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [D-060] 회귀 방어 — 릴레이가 브로커 확인(ack)을 기다리는 동안 업무 트랜잭션의 Outbox 삽입이 막히면 안 된다.
 *
 * <p>수정 전에는 삽입이 확인 지연(3초)만큼 막혔다(2.99초). [결정 215]로 릴레이 트랜잭션을 READ COMMITTED로 돌려
 * 간격 잠금을 없앴다. 수정 전 상태는 {@link OutboxRelay}의 격리 수준 설정을 지우면 다시 볼 수 있다.
 *
 * <p>S7(결정 210, 브로커 재시작)에서 Kafka가 멈춘 동안 order의 {@code insert into outbox_event}가
 * {@code LOCK WAIT}에 걸려 Hikari 풀이 고갈되고 주문이 500으로 실패했다. {@link OutboxRelay#relay()}는
 * {@code lockPendingBatch}({@code FOR UPDATE SKIP LOCKED})로 잠근 트랜잭션 안에서 발행하고 확인을 기다린다.
 * 브로커가 응답하지 않으면 그 기다림이 그대로 잠금 유지 시간이 된다.
 *
 * <p><b>브로커를 멈추지 않는다.</b> 재현하려는 것은 "확인이 늦을 때 잠금이 얼마나 오래 남는가"이므로,
 * 확인을 {@link #ACK_DELAY}만큼 늦게 주는 발행 포트로 그 시간을 고정한다. 실제 브로커 클라이언트의
 * 타임아웃(Kafka는 수십 초)보다 짧게 잡아 테스트 시간을 줄인다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class RelayLockHoldTest {

    /** 브로커가 응답하지 않는 시간을 대신한다. */
    private static final Duration ACK_DELAY = Duration.ofSeconds(3);
    /** 잠금 경합이 없으면 삽입은 이보다 훨씬 빨리 끝난다. */
    private static final Duration UNBLOCKED_INSERT = Duration.ofSeconds(1);

    @Autowired
    OutboxEventRepository repository;
    @Autowired
    OutboxProperties properties;
    @Autowired
    OutboxMetrics metrics;
    @Autowired
    PlatformTransactionManager transactionManager;

    private final ExecutorService relayThread = Executors.newSingleThreadExecutor();

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @AfterEach
    void stop() {
        relayThread.shutdownNow();
    }

    private OutboxEvent pending(String orderNo) {
        return OutboxEvent.pending(UUID.randomUUID().toString(), "Order", orderNo,
                "OrderCreated", "stove.order.v1", orderNo, "{}");
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("[D-060] 릴레이가 브로커 확인을 기다리는 동안에도 다른 주문의 Outbox 삽입은 막히지 않아야 한다")
    void businessInsertIsNotBlockedWhileTheRelayAwaitsAck() throws Exception {
        repository.save(pending("ORD-A-" + UUID.randomUUID()));
        CountDownLatch published = new CountDownLatch(1);
        MessagePublisher slowBroker = message -> {
            published.countDown();
            return CompletableFuture.runAsync(() -> sleep(ACK_DELAY));
        };
        OutboxRelay relay = new OutboxRelay(repository, slowBroker, properties.batchSize(), properties.maxRetry(),
                properties.maxBatchesPerCycle(), metrics, new TransactionTemplate(transactionManager));

        Future<?> relaying = relayThread.submit(relay::relay);
        assertThat(published.await(10, TimeUnit.SECONDS))
                .as("릴레이가 첫 행을 집어 발행을 시작해야 그 뒤 삽입이 '확인 대기 중'에 일어난다")
                .isTrue();

        long startedAt = System.nanoTime();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                repository.saveAndFlush(pending("ORD-B-" + UUID.randomUUID())));
        Duration insertTook = Duration.ofNanos(System.nanoTime() - startedAt);
        relaying.get(30, TimeUnit.SECONDS);

        // 기대: 다른 주문(다른 키)의 삽입은 릴레이의 확인 대기와 무관하게 곧바로 끝난다.
        // 수정 전: 릴레이 트랜잭션이 확인을 기다리며 간격 잠금을 쥐어, 삽입이 확인 지연(3초)만큼 막혔다.
        // 영향: 브로커가 멈추면 업무 요청이 커넥션을 쥔 채 잠금을 기다리다 풀이 고갈되어 실패한다(S7 Kafka 39·45건).
        assertThat(insertTook)
                .as("Outbox 삽입이 릴레이의 브로커 확인 대기(%s)에 막혔다", ACK_DELAY)
                .isLessThan(UNBLOCKED_INSERT);
    }
}
