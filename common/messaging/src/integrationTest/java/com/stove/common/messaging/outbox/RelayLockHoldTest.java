package com.stove.common.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.stove.common.event.MessagePublisher;
import com.stove.common.messaging.config.OutboxProperties;
import com.stove.common.testcontainers.InfraContainers;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * <p>S7(결정 210, 브로커 재시작)에서 Kafka가 멈춘 동안 order의 {@code insert into outbox_event}가
 * {@code LOCK WAIT}에 걸려 Hikari 풀이 고갈되고 주문이 500으로 실패했다. {@link OutboxRelay#relay()}는
 * {@code lockPendingBatch}({@code FOR UPDATE SKIP LOCKED})로 잠근 트랜잭션 안에서 발행하고 확인을 기다린다.
 * REPEATABLE READ에서는 그 잠금이 간격까지 덮어, 확인을 기다리는 시간이 그대로 삽입이 막히는 시간이 됐다
 * (수정 전 2.99초 대기). [결정 215]로 릴레이 트랜잭션을 READ COMMITTED로 돌렸다.
 *
 * <p><b>브로커를 멈추지 않고, 시간에 기대지 않는다</b>(PR #228 R2). 확인은 테스트가 직접 쥔 {@link CompletableFuture}다.
 * 확인을 붙든 채 다른 주문의 삽입이 {@link #INSERT_LIMIT_SECONDS}초 안에 끝나는지만 본다. 수정 전 코드에서는 확인을
 * 놓아줄 때까지 삽입이 끝나지 않으므로(잠금 대기 기본 50초) 판정이 실행 속도에 흔들리지 않는다.
 * 수정 전 상태는 {@link OutboxRelay}의 격리 수준 설정을 지우면 다시 볼 수 있다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class RelayLockHoldTest {

    /** 잠금 경합이 없는 삽입에는 넉넉하고, MySQL 잠금 대기 기본값(50초)보다는 짧다. */
    private static final long INSERT_LIMIT_SECONDS = 10;

    @Autowired
    OutboxEventRepository repository;
    @Autowired
    OutboxProperties properties;
    @Autowired
    OutboxMetrics metrics;
    @Autowired
    PlatformTransactionManager transactionManager;

    private final ExecutorService relayThread = Executors.newSingleThreadExecutor();
    private final ExecutorService insertThread = Executors.newSingleThreadExecutor();

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @AfterEach
    void stop() {
        relayThread.shutdownNow();
        insertThread.shutdownNow();
    }

    private OutboxEvent pending(String orderNo) {
        return OutboxEvent.pending(UUID.randomUUID().toString(), "Order", orderNo,
                "OrderCreated", "stove.order.v1", orderNo, "{}");
    }

    @Test
    @DisplayName("[D-060] 릴레이가 브로커 확인을 기다리는 동안에도 다른 주문의 Outbox 삽입은 막히지 않아야 한다")
    void businessInsertIsNotBlockedWhileTheRelayAwaitsAck() throws Exception {
        repository.save(pending("ORD-A-" + UUID.randomUUID()));
        CompletableFuture<Void> heldAck = new CompletableFuture<>();
        CountDownLatch published = new CountDownLatch(1);
        MessagePublisher unresponsiveBroker = message -> {
            published.countDown();
            return heldAck;
        };
        OutboxRelay relay = new OutboxRelay(repository, unresponsiveBroker, properties.batchSize(), properties.maxRetry(),
                properties.maxBatchesPerCycle(), metrics, transactionManager);

        Future<?> relaying = relayThread.submit(relay::relay);
        Future<?> inserting = null;
        try {
            assertThat(published.await(10, TimeUnit.SECONDS))
                    .as("릴레이가 첫 행을 집어 발행을 시작해야 그 뒤 삽입이 '확인 대기 중'에 일어난다")
                    .isTrue();

            inserting = insertThread.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                    status -> repository.saveAndFlush(pending("ORD-B-" + UUID.randomUUID()))));
            try {
                inserting.get(INSERT_LIMIT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException blocked) {
                // 기대: 다른 주문(다른 키)의 삽입은 릴레이의 확인 대기와 무관하게 끝난다.
                // 수정 전: 릴레이 트랜잭션이 확인을 기다리며 간격 잠금을 쥐어, 확인을 놓아줄 때까지 삽입이 막혔다.
                // 영향: 브로커가 멈추면 업무 요청이 커넥션을 쥔 채 잠금을 기다리다 풀이 고갈되어 실패한다(S7 Kafka 39·45건).
                fail("릴레이가 브로커 확인을 기다리는 동안 다른 주문의 Outbox 삽입이 %d초 안에 끝나지 않았다", INSERT_LIMIT_SECONDS);
            }
            assertThat(heldAck)
                    .as("삽입이 끝난 시점에 릴레이는 아직 확인을 기다리고 있어야 '확인 대기 중' 판정이다")
                    .isNotDone();
        } finally {
            heldAck.complete(null);
            relaying.get(30, TimeUnit.SECONDS);
            if (inserting != null) {
                inserting.get(60, TimeUnit.SECONDS);
            }
        }
    }
}
