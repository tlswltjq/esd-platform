package com.stove.license.api.listener;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.kafka.EventHeaders;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.event.payload.PaymentCompletedEvent;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.license.core.domain.License;
import com.stove.license.core.domain.LicenseRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * 시나리오 R-03 — <b>지급 서비스가 중단된 사이 결제가 완료된다.</b>
 *
 * <p>{@code docs/chaos.md} 8-2 가 {@code license-stopped} 로 실측한 자리다. 15분을 죽여 두고
 * 부하를 흘린 뒤 복구하니 600/600 이 정상으로 끝났다 — <b>회차 기록으로는 남았지만 회귀로는
 * 지켜지지 않는다.</b> 그 실측은 사람이 스크립트를 돌려야 나오고, CI 는 한 번도 돌리지 않는다.
 *
 * <p>여기서 그 시나리오를 결정적으로 재현한다. 죽이는 대상은 컨테이너가 아니라
 * <b>리스너 컨테이너</b>다 — 이 시나리오에서 멈추는 것은 브로커도 DB 도 아니고
 * "이벤트를 받아 지급하는 쪽" 이므로, 정확히 그것만 멈춰야 재려던 것이 남는다
 * ({@code docs/chaos.md} 2장이 MySQL 컨테이너를 끄지 않기로 한 것과 같은 판단이다).
 *
 * <p>판정 순서가 곧 이 문서의 논지다.
 * <ol>
 *   <li><b>중단 전에 한 건이 지급된다</b> — 컨슈머가 그룹에 붙어 오프셋을 커밋한 상태를 만든다.
 *       이게 없으면 뒤의 "이어받았다" 가 "처음부터 읽었다" 와 구분되지 않는다</li>
 *   <li><b>중단 중에는 한 건도 지급되지 않는다</b> — 주입 확인이 본체다.
 *       여기가 초록이 아니면 아래 복구 판정은 아무것도 증명하지 않는다</li>
 *   <li><b>재기동하면 밀린 것을 전부 따라잡는다</b></li>
 *   <li><b>따라잡기가 이중 지급이 되지 않는다</b> — 재배달은 at-least-once 의 정상 동작이다</li>
 * </ol>
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class ConsumerRestartCatchUpTest {

    /** 지급이 전파되는 데 걸리는 시간. 브로커 홉 하나라 대개 1초 안이다. */
    private static final Duration PROPAGATION = Duration.ofSeconds(30);

    /**
     * "안 일어난다" 를 재는 창.
     *
     * <p>없는 것을 단언하려면 시간이 필요하다 — 순간을 보면 <b>아직 안 온 것</b>과
     * <b>오지 않는 것</b>이 같아 보인다. 정상 전파가 1초 안이므로 5초면 그 둘이 갈린다.
     */
    private static final Duration SILENCE = Duration.ofSeconds(5);

    @Autowired
    KafkaListenerEndpointRegistry registry;
    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    LicenseRepository licenseRepository;
    @Autowired
    ObjectMapper objectMapper;

    /**
     * 어느 판정에서 실패해도 컨슈머를 되살려 놓는다.
     *
     * <p>멈춘 채로 나가면 같은 JVM 을 쓰는 다음 테스트 클래스가 <b>이 테스트의 장애를 물려받는다.</b>
     * 장애 주입 회차가 원상복구를 확인하고 끝내는 것과 같은 이유다({@code scripts/chaos/README.md}).
     */
    @AfterEach
    void restoreConsumer() {
        startLicense();
    }

    // ── 장애 주입 ────────────────────────────────────────────────────

    /**
     * 죽일 대상이 실제로 있는지 먼저 확인한다.
     *
     * <p><b>비어 있으면 "멈췄다" 가 공허하게 참이 된다</b> — {@code anyMatch} 는 빈 컬렉션에서
     * 언제나 false 라, 리스너를 하나도 못 찾은 회차가 "정상적으로 멈췄다" 로 읽힌다.
     * 그러면 이벤트는 그대로 소비되는데 판정은 초록이다.
     *
     * <p>이 저장소가 세 번 밟은 부류다(D-021 · D-023 · D-031). 장애를 주입하는 테스트가
     * 그 함정을 자기 하네스에 다시 파면 안 된다 — <b>주입 확인이 본체다.</b>
     */
    private Collection<MessageListenerContainer> listeners() {
        Collection<MessageListenerContainer> containers = registry.getListenerContainers();
        assertThat(containers)
                .as("리스너 컨테이너를 하나도 못 찾았다 — 죽일 대상이 없으면 이 장은 아무것도 재지 않는다")
                .isNotEmpty();
        return containers;
    }

    private void stopLicense() {
        listeners().forEach(MessageListenerContainer::stop);
        Awaitility.await("리스너 정지")
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .pollDelay(Duration.ZERO)
                .until(() -> listeners().stream().noneMatch(MessageListenerContainer::isRunning));
    }

    private void startLicense() {
        listeners().forEach(container -> {
            if (!container.isRunning()) {
                container.start();
            }
        });
    }

    private boolean licenseConsumerRunning() {
        return listeners().stream().anyMatch(MessageListenerContainer::isRunning);
    }

    // ── 결제 이벤트를 실제 브로커로 흘린다 ───────────────────────────

    private String publishPaymentCompleted() {
        String orderNo = "ORD-" + UUID.randomUUID();
        PaymentCompletedEvent event = PaymentCompletedEvent.of(
                1L, orderNo, 42L, 10_000L, "CARD",
                List.of(new OrderLine(1L, "게임 1", 1001L, 10_000L, 1)));
        publish(orderNo, event);
        return orderNo;
    }

    /** 같은 이벤트를 한 번 더 — 브로커 재시도·리밸런싱이 만드는 재배달과 같은 모양이다. */
    private void publishAgain(String orderNo, String eventId) {
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                eventId, java.time.Instant.now(), 1L, orderNo, 42L, 10_000L, "CARD",
                List.of(new OrderLine(1L, "게임 1", 1001L, 10_000L, 1)));
        publish(orderNo, event);
    }

    private void publish(String orderNo, PaymentCompletedEvent event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("이벤트 직렬화 실패", e);
        }
        ProducerRecord<String, String> record =
                new ProducerRecord<>(Topics.PAYMENT, orderNo, payload);
        record.headers().add(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE,
                EventType.PAYMENT_COMPLETED.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record);
        kafkaTemplate.flush();
    }

    // ── 판정 ─────────────────────────────────────────────────────────

    private List<License> licensesOf(String orderNo) {
        return licenseRepository.findByOrderNo(orderNo);
    }

    private void awaitIssued(String orderNo) {
        Awaitility.await("%s 지급".formatted(orderNo))
                .atMost(PROPAGATION)
                .pollInterval(Duration.ofMillis(200))
                .pollDelay(Duration.ZERO)
                .until(() -> !licensesOf(orderNo).isEmpty());
    }

    /**
     * 실패했을 때 <b>누가 지급됐는지</b>를 남긴다.
     *
     * <p>Awaitility 의 기본 메시지는 "조건이 성립하지 않았다" 까지만 말한다. 여기서 알아야 하는 것은
     * "멈췄는데도 지급됐다" 인지 "애초에 안 멈췄다" 인지이고, 그 둘은 지급된 건수로 갈린다.
     */
    private void awaitStaysUnissued(List<String> orderNos) {
        try {
            Awaitility.await("중단 중 미지급")
                    .during(SILENCE)
                    .atMost(SILENCE.plusSeconds(5))
                    .pollInterval(Duration.ofMillis(200))
                    .pollDelay(Duration.ZERO)
                    .until(() -> orderNos.stream().allMatch(orderNo -> licensesOf(orderNo).isEmpty()));
        } catch (ConditionTimeoutException e) {
            List<String> issued = orderNos.stream()
                    .filter(orderNo -> !licensesOf(orderNo).isEmpty())
                    .toList();
            throw new AssertionError("""
                    컨슈머를 멈췄는데 지급이 일어났다 — 멈춘 것이 지급 경로가 아니었다는 뜻이다.
                    지급된 주문: %s (전체 %d건 중)
                    리스너 상태: %s""".formatted(issued, orderNos.size(), listenerStates()), e);
        }
    }

    private String listenerStates() {
        return listeners().stream()
                .map(container -> "%s=%s".formatted(container.getListenerId(), container.isRunning()))
                .toList()
                .toString();
    }

    @Test
    @DisplayName("license 가 중단된 사이 결제가 완료돼도, 재기동하면 밀린 지급을 전부 따라잡는다")
    void stoppedConsumerCatchesUpAfterRestart() {
        // ① 중단 전 — 컨슈머가 살아서 그룹에 붙어 있다
        String beforeOutage = publishPaymentCompleted();
        awaitIssued(beforeOutage);

        // ② 중단 — 이 시나리오에서 죽는 것은 '받아서 지급하는 쪽' 하나다
        stopLicense();
        assertThat(licenseConsumerRunning())
                .as("멈추지 않았다면 아래 판정은 '견뎠다'가 아니라 '장애가 없었다'다")
                .isFalse();

        // ③ 중단 중에도 결제는 계속 완료된다 — 돈은 이미 움직였다
        List<String> duringOutage = List.of(
                publishPaymentCompleted(), publishPaymentCompleted(), publishPaymentCompleted());
        awaitStaysUnissued(duringOutage);

        // ④ 재기동
        startLicense();

        // ⑤ 커밋된 오프셋부터 이어받아 밀린 것을 전부 소화한다
        duringOutage.forEach(this::awaitIssued);
        assertThat(duringOutage)
                .allSatisfy(orderNo -> assertThat(licensesOf(orderNo))
                        .as("중단 중 결제분 %s 가 재기동 후 지급됐다", orderNo)
                        .hasSize(1));
    }

    @Test
    @DisplayName("따라잡기가 이중 지급이 되지 않는다 — 재배달은 at-least-once 의 정상 동작이다")
    void catchUpDoesNotDoubleIssue() {
        String orderNo = "ORD-" + UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        stopLicense();
        publishAgain(orderNo, eventId);
        publishAgain(orderNo, eventId);   // 중단 중에 같은 이벤트가 두 번 쌓였다
        awaitStaysUnissued(List.of(orderNo));

        startLicense();
        awaitIssued(orderNo);

        // 재기동이 둘 다 소화하지만 지급은 한 번이다 — ProcessedEventGuard 가 흡수한다
        publishAgain(orderNo, eventId);   // 재기동 후에도 한 번 더
        Awaitility.await("중복 흡수")
                .during(SILENCE)
                .atMost(SILENCE.plusSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .pollDelay(Duration.ZERO)
                .until(() -> licensesOf(orderNo).size() == 1);

        assertThat(licensesOf(orderNo))
                .as("같은 eventId 를 세 번 받았고 지급은 한 번이다")
                .hasSize(1);
    }
}
