package com.stove.common.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.messaging.outbox.OutboxEvent.OutboxStatus;
import com.stove.common.testcontainers.InfraContainers;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 시나리오 R-02 — <b>발행 커밋 직후 프로세스가 중단되고 재기동한다.</b>
 *
 * <p>{@link OutboxRelay} 의 클래스 주석이 폴링을 지우지 말라며 근거로 든 문장이 하나 있다:
 * <i>"커밋과 신호 사이에 죽거나 PENDING 이 남은 채로 재기동하면 신호는 오지 않는다."</i>
 * 정확성을 보증하는 것은 커밋 후 깨우기가 아니라 <b>폴링</b>이라는 뜻인데,
 * <b>그 문장을 지키는 테스트가 없었다.</b> 지금 구조에서는 우연히 맞고,
 * 누가 {@code @TransactionalEventListener(AFTER_COMMIT)} 신호를 넣으면서
 * "이제 폴링은 낭비" 라고 판단하는 순간 조용히 깨진다.
 *
 * <p><b>중단을 어떻게 흉내내는가</b> — 컨테이너를 죽이지 않는다. 이 시나리오에서 죽는 것은
 * 인프라가 아니라 <b>릴레이를 들고 있던 프로세스</b>이므로, 죽이는 대상도 그것이어야 한다.
 * {@code relay-enabled=false} 컨텍스트가 "릴레이 없이 도는 프로세스"고,
 * {@link #restartedRelay()} 가 만드는 인스턴스가 "그 뒤에 새로 뜬 프로세스"다.
 * <b>새 인스턴스는 적재를 목격하지 않았다</b> — 그것이 이 시나리오의 전부다.
 *
 * <p><b>주입 확인이 본체다</b>({@code docs/chaos.md} 9장 1번). 첫 판정이
 * {@link #relayIsActuallyAbsent()} 인 이유가 그것이다 — 릴레이가 살아 있는 채로 잰
 * "복구했다" 는 복구를 잰 것이 아니라 아무 일도 없었음을 잰 것이다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class RelayRestartRecoveryTest {

    @Autowired
    OutboxEventRepository repository;
    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    OutboxProperties properties;
    @Autowired
    OutboxMetrics metrics;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    ApplicationContext context;

    /**
     * 전역으로 세는 판정이 있어 앞 회차의 행이 남으면 섞인다
     * ({@code lockPendingBatch} 는 이 테이블 전체를 본다).
     */
    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    /**
     * 재기동한 프로세스의 릴레이.
     *
     * <p>자동 구성이 만드는 것과 <b>같은 인자</b>로 짓는다({@code MessagingAutoConfiguration#outboxRelay}).
     * 여기서 다른 것을 주면 재기동을 재는 것이 아니라 이 테스트만의 배선을 재게 된다.
     */
    private OutboxRelay restartedRelay() {
        return new OutboxRelay(repository, kafkaTemplate, properties, metrics,
                new TransactionTemplate(transactionManager));
    }

    /** 커밋만 되고 아무도 발행하지 않은 상태 — 중단된 프로세스가 남긴 것. */
    private OutboxEvent committedButUnpublished(String orderNo) {
        return repository.save(OutboxEvent.pending(
                UUID.randomUUID().toString(), "Order", orderNo,
                "OrderCreated", "stove.order.v1", orderNo, "{}"));
    }

    private OutboxStatus statusOf(OutboxEvent event) {
        return repository.findById(event.getId()).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("중단 상태가 진짜다 — 이 컨텍스트에는 릴레이가 없다")
    void relayIsActuallyAbsent() {
        assertThat(context.getBeanNamesForType(OutboxRelay.class))
                .as("릴레이가 살아 있으면 아래 판정들은 '복구했다'가 아니라 '중단된 적이 없다'가 된다")
                .isEmpty();
    }

    @Test
    @DisplayName("릴레이가 중단된 동안 커밋된 이벤트는 아무도 발행하지 않는다")
    void nothingIsPublishedWhileTheRelayIsDown() {
        OutboxEvent event = committedButUnpublished("ORD-" + UUID.randomUUID());

        assertThat(statusOf(event))
                .as("발행할 주체가 없으므로 PENDING 그대로여야 한다")
                .isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("재기동한 릴레이가 그 이벤트를 발행한다 — 적재를 목격하지 않았는데도")
    void restartedRelayPublishesWhatItNeverSawBeingWritten() {
        OutboxEvent event = committedButUnpublished("ORD-" + UUID.randomUUID());

        restartedRelay().relay();

        assertThat(statusOf(event))
                .as("커밋 후 깨우기 신호는 이 인스턴스에 올 수 없다. 폴링이 유일한 경로다")
                .isEqualTo(OutboxStatus.SENT);
    }

    /**
     * 밀린 것이 배치 하나를 넘겨도 한 회차에 다 나가야 한다.
     *
     * <p>중단이 길었다는 것은 곧 적체가 배치보다 크다는 뜻이다. {@code relay()} 의 루프가
     * "배치를 다 채웠으면 쉬지 않는다" 로 그 자리를 맡고 있는데, 그 루프를 고정 주기로 되돌리면
     * <b>재기동 직후가 가장 느려진다</b> — 정확히 가장 급한 순간에.
     */
    @Test
    @DisplayName("배치 크기를 넘겨 밀린 것도 재기동 첫 회차에 전부 나간다")
    void oneCycleDrainsMoreThanASingleBatch() {
        int overflowing = properties.batchSize() + 5;
        List<OutboxEvent> backlog = java.util.stream.IntStream.range(0, overflowing)
                .mapToObj(i -> committedButUnpublished("ORD-%s-%d".formatted(UUID.randomUUID(), i)))
                .toList();

        restartedRelay().relay();

        assertThat(backlog).allSatisfy(event ->
                assertThat(statusOf(event)).isEqualTo(OutboxStatus.SENT));
        assertThat(repository.countByStatus(OutboxStatus.PENDING)).isZero();
    }

    /**
     * 재기동은 <b>포기한 것을 되살리는 사건이 아니다.</b>
     *
     * <p>DEAD 는 재시도 예산을 다 쓴 상태이고, 회수는 원인을 제거한 뒤 사람이 내리는 판단이다
     * ({@link OutboxEvent#requeue()}). 재기동만으로 되살아나면 브로커가 죽어 있는 동안
     * 앱이 재시작될 때마다 DEAD 전량이 다시 브로커로 몰려간다.
     */
    @Test
    @DisplayName("재기동이 DEAD 를 되살리지는 않는다 — 회수는 사람의 판단이다")
    void restartDoesNotResurrectDeadEvents() {
        OutboxEvent dead = committedButUnpublished("ORD-" + UUID.randomUUID());
        dead.markFailed("브로커 응답 없음", 1);
        repository.save(dead);
        assertThat(statusOf(dead)).isEqualTo(OutboxStatus.DEAD);

        restartedRelay().relay();

        assertThat(statusOf(dead)).isEqualTo(OutboxStatus.DEAD);
    }

    /**
     * 백오프는 재기동을 건너뛰는 문이 아니다.
     *
     * <p>이게 깨지면 "죽었다 살아나면 즉시 재시도" 가 되고, 브로커가 원인인 장애에서
     * 재시작 루프가 그대로 재시도 폭주가 된다.
     */
    @Test
    @DisplayName("다음 시도 시각이 아직 안 된 이벤트는 재기동해도 기다린다")
    void backOffSurvivesRestart() {
        OutboxEvent waiting = committedButUnpublished("ORD-" + UUID.randomUUID());
        waiting.holdUntil(Instant.now().plus(10, ChronoUnit.MINUTES));
        repository.save(waiting);

        restartedRelay().relay();

        assertThat(statusOf(waiting)).isEqualTo(OutboxStatus.PENDING);
    }
}
