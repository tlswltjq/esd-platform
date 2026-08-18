package com.stove.license.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.kafka.EventHeaders;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.event.payload.PaymentCompletedEvent;
import com.stove.common.kafka.DeadLetterTopics;
import com.stove.common.kafka.ops.DltOpsService;
import com.stove.common.kafka.ops.DltRecordResponse;
import com.stove.common.messaging.outbox.OutboxEvent;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.license.core.domain.License;
import com.stove.license.core.domain.LicenseRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 시나리오 R-07 — <b>보류(DLT)는 정말 되돌릴 수 있는가.</b>
 *
 * <h2>이 판정이 무엇을 지키는가</h2>
 *
 * <p>[D-027] 이 자동 환불 대신 <b>보류</b>를 택한 근거가 한 문장이었다:
 * <i>"환불은 되돌리기 어렵고 보류는 되돌리기 쉽다. 판단이 불확실할 때는 되돌릴 수 있는 쪽으로 간다."</i>
 * 그 판단 위에 저장소 장애 분기({@link KafkaErrorHandlerConfig#isStorageFailure})가 서 있고,
 * {@code chaos.md} 8-5 가 "알람 1분 + 복구 1초" 라고 그 크기까지 적었다.
 *
 * <p><b>그런데 그 '되돌리기' 가 한 번도 끝까지 실행된 적이 없었다.</b>
 *
 * <ul>
 *   <li>{@code KafkaErrorHandlerConfigTest} — 어느 경로가 DLT 로 가는지까지. 대역이다</li>
 *   <li>{@code DltOpsServiceTest} — 재투입의 기계장치(원본 토픽·키·헤더·커밋 순서). 목이다</li>
 *   <li>{@code DeadLetterE2eTest} — <b>"DLT 가 0건이다"</b>. 재투입을 부르지 않는다</li>
 * </ul>
 *
 * <p>즉 <b>실패 → DLT → 원인 제거 → 재투입 → 실제 지급 완료</b> 라는 고리는
 * 어느 층에서도 이어져 돌지 않았다. 설계 판단의 근거인데 회귀가 없었다.
 *
 * <h2>왜 저장소 장애로 재현하는가</h2>
 *
 * <p>license 의 DLT 에 들어오는 것이 그것뿐이기 때문이다. 지급 실패(보상함)는
 * <b>일부러 DLT 로 보내지 않는다</b> — {@link KafkaErrorHandlerConfig} 주석이 적어 둔 대로
 * 이미 환불된 결제에 재투입으로 라이선스를 발급하게 되기 때문이다.
 * 그래서 "재투입해서 살릴 수 있는 보류" 는 정확히 <b>저장소 장애로 보류된 지급</b> 하나다.
 *
 * <p>{@code DataAccessResourceFailureException} 을 저장 시점에 던진다 — 관문 1이 보는
 * 원인 그대로다. 컨테이너를 끄지 않는 이유는 {@code chaos.md} 2장과 같다:
 * MySQL 은 Inbox·Outbox 도 같이 쓰므로 끄면 재려던 것이 사라진다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class DeadLetterReplayRecoveryTest {

    private static final String DLT = DeadLetterTopics.nameFor(Topics.PAYMENT);

    /**
     * DLT 로 떨어지기까지 기다리는 창.
     *
     * <p>{@code ConsumerRetryPolicy} 가 최초 1회 + 재시도 3회이고 백오프가 1+2+4=7초다.
     * 40초는 그 위에 컨슈머 폴링과 브로커 왕복을 넉넉히 얹은 값이다.
     */
    private static final Duration UNTIL_PARKED = Duration.ofSeconds(40);

    private static final Duration UNTIL_ISSUED = Duration.ofSeconds(30);

    /** 원장을 감출 이름. 되돌리는 것이 {@code RENAME} 한 줄이라 {@code fault.sh} 의 {@code GRANT} 와 성질이 같다. */
    private static final String HIDDEN = "license_chaos_hidden";

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    OutboxEventRepository outboxEventRepository;
    @Autowired
    LicenseRepository licenseRepository;
    @Autowired
    DltOpsService dltOpsService;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JdbcTemplate jdbcTemplate;

    /**
     * 어떤 실패로 끝나도 원장을 되돌려 놓는다.
     *
     * <p>감춘 채로 나가면 같은 JVM 을 쓰는 다음 테스트 클래스가 이 장애를 물려받는다 —
     * 장애 주입 회차가 원상복구를 확인하고 끝내는 것과 같은 이유다({@code scripts/chaos/README.md}).
     */
    @AfterEach
    void restoreStorage() {
        if (tableExists(HIDDEN)) {
            jdbcTemplate.execute("RENAME TABLE " + HIDDEN + " TO license");
        }
    }

    private boolean tableExists(String name) {
        Integer found = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema = database() and table_name = ?", Integer.class, name);
        return found != null && found > 0;
    }

    /**
     * 저장소가 답을 못 주는 상태 — 관문 1이 '지급 불가' 가 아니라 '판단 불가' 로 읽어야 하는 원인이다.
     *
     * <h2>왜 목이 아니라 테이블을 감추는가</h2>
     *
     * <p>처음에는 {@code @MockitoSpyBean LicenseRepository} 로 예외를 던졌다. 그게
     * <b>같은 모듈의 다른 판정을 깨뜨렸다</b> — 빈 오버라이드가 컨텍스트 캐시 키를 바꿔
     * license 모듈에 <b>컨텍스트가 한 벌 더</b> 생기고, 그 컨텍스트의 카프카 리스너가
     * <b>같은 컨슈머 그룹</b>으로 붙는다. 그러면 {@code ConsumerRestartCatchUpTest} 가
     * 자기 리스너를 멈춰도 다른 컨텍스트의 리스너가 이벤트를 가져가서
     * "컨슈머가 멈춘 사이" 라는 전제가 성립하지 않는다. 실제로 그 판정이 빨개졌고,
     * 리스너 상태가 {@code false} 인데 지급이 일어난 것이 증거였다.
     *
     * <p>그래서 <b>컨텍스트 키를 건드리지 않는 방법</b>으로 바꿨다. 원장 테이블만 다른 이름으로
     * 옮기면 이 서비스의 조회·저장이 {@code DataAccessException} 으로 떨어지고
     * Inbox·Outbox 는 그대로 돈다 — {@code fault.sh} 의 {@code license-table-denied} 와 같은 경계다.
     * 목보다 실물에 가깝기도 하다: 예외 클래스를 우리가 고르지 않는다.
     */
    private void breakStorage() {
        jdbcTemplate.execute("RENAME TABLE license TO " + HIDDEN);
    }

    private String publishPaymentCompleted() {
        String orderNo = "ORD-" + UUID.randomUUID();
        PaymentCompletedEvent event = PaymentCompletedEvent.of(
                1L, orderNo, 42L, 10_000L, "CARD",
                List.of(new OrderLine(1L, "게임 1", 1001L, 10_000L, 1)));
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("이벤트 직렬화 실패", e);
        }
        ProducerRecord<String, String> record = new ProducerRecord<>(Topics.PAYMENT, orderNo, payload);
        record.headers().add(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE,
                EventType.PAYMENT_COMPLETED.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record);
        kafkaTemplate.flush();
        return orderNo;
    }

    private List<License> licensesOf(String orderNo) {
        return licenseRepository.findByOrderNo(orderNo);
    }

    /**
     * <b>토픽이 아직 없는 것은 "보류 전" 이다.</b>
     *
     * <p>DLT 는 첫 발행 때 만들어지므로, 재시도가 소진되기 전에는 토픽 자체가 존재하지 않는다.
     * {@code peek} 은 그때 {@code IllegalArgumentException("그런 토픽이 없다")} 를 던지고
     * Awaitility 는 예외를 "아직 아니다" 로 보지 않고 그대로 올린다 —
     * 그래서 첫 폴링에서 회차가 끝나 버린다(실제로 그렇게 한 번 빨개졌다).
     *
     * <p>여기서 삼키는 것은 <b>이 조건 하나</b>다. 다른 실패는 그대로 올린다 —
     * 브로커가 죽은 것과 토픽이 아직 없는 것은 대응이 다르다.
     */
    private boolean parkedInDeadLetter(String orderNo) {
        try {
            return dltOpsService.peek(DLT, 200).stream()
                    .map(DltRecordResponse::key)
                    .anyMatch(orderNo::equals);
        } catch (IllegalArgumentException topicNotCreatedYet) {
            return false;
        }
    }

    private List<String> outboxTypesOf(String orderNo) {
        return outboxEventRepository.findAll().stream()
                .filter(event -> orderNo.equals(event.getAggregateId()))
                .map(OutboxEvent::getEventType)
                .toList();
    }

    @Test
    @DisplayName("저장소 장애로 보류된 지급은, 저장소가 돌아온 뒤 재투입하면 지급까지 끝난다")
    void parkedIssuanceIsRecoverableByReplay() {
        breakStorage();
        String orderNo = publishPaymentCompleted();

        // ① 보류 — 환불이 아니라 DLT 로 갔다
        Awaitility.await("DLT 보류")
                .atMost(UNTIL_PARKED)
                .pollInterval(Duration.ofSeconds(1))
                .pollDelay(Duration.ZERO)
                .until(() -> parkedInDeadLetter(orderNo));

        // ② 원인 제거 — 재투입 전에 반드시 이것이 먼저다(DltOpsService#replay 주석).
        //    원장을 되돌린 뒤에야 "지급됐는가" 를 물을 수 있다 — 감춰진 동안은 조회 자체가 실패한다.
        restoreStorage();

        assertThat(licensesOf(orderNo))
                .as("저장소가 죽어 있는 동안이었으므로 지급되지 않았다")
                .isEmpty();
        assertThat(outboxTypesOf(orderNo))
                .as("""
                        관문 1의 핵심 — 저장소 장애는 '지급 불가' 가 아니라 '판단 불가' 다.
                        여기에 LicenseIssueFailed 가 있으면 정상 결제가 자동 환불된 것이고,
                        그게 D-027 이 실측으로 잡은 결함(60초 장애에 8건)이다.""")
                .doesNotContain(EventType.LICENSE_ISSUE_FAILED);

        // ③ 재투입
        int replayed = dltOpsService.replay(DLT, 200);
        assertThat(replayed)
                .as("되돌린 건수가 0이면 DLT 이름 규칙이나 컨슈머 그룹이 어긋난 것이다")
                .isPositive();

        // ④ 되돌아왔다 — 이 한 줄이 "보류는 되돌릴 수 있다" 의 전부다
        Awaitility.await("재투입 후 지급")
                .atMost(UNTIL_ISSUED)
                .pollInterval(Duration.ofMillis(500))
                .pollDelay(Duration.ZERO)
                .untilAsserted(() -> assertThat(licensesOf(orderNo))
                        .as("""
                                재투입했는데 지급되지 않았다면 보류는 되돌릴 수 없는 것이고,
                                그러면 D-027 이 환불 대신 보류를 택한 근거가 무너진다.
                                Inbox 가드가 먼저 막는 경우(D-030)와 구분해서 본다 —
                                첫 시도가 실패했으므로 가드 행은 롤백돼 있어야 한다.""")
                        .hasSize(1));

        assertThat(outboxTypesOf(orderNo))
                .as("지급이 끝났으면 소유 상태 이벤트도 나가야 한다 — download 가 그것만 본다")
                .contains(EventType.LICENSE_ISSUED);
    }
}
