package com.stove.license.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.event.EventType;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.messaging.inbox.ProcessedEventRepository;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.license.core.domain.LicenseRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * [D-030] <b>"이벤트를 다시 읽어 복구한다"가 이 시스템에서는 성립하지 않는다.</b>
 *
 * <p>결제 완료 이벤트는 카프카에 남아 있으므로, license 의 원장이 유실돼도 오프셋을 앞으로
 * 되돌리면 되살릴 수 있어야 한다. 그런데 Inbox 멱등 가드가 {@code (event_id, consumer_group)} 로
 * 먼저 막는다 — <b>되읽은 이벤트를 전부 "이미 처리함"으로 판정하고 건너뛴다.</b>
 * 라이선스 행만 사라지고 가드 행은 살아 있으면 <b>한 건도 복구되지 않는다.</b>
 *
 * <p>운영이 쓸 수 있는 재처리 수단은 셋뿐이고 <b>셋 다 eventId 를 보존한다</b> —
 * 오프셋 리셋, {@code DltOpsService#replay}(원본 헤더를 그대로 되돌린다),
 * Outbox 재적재. 즉 <b>eventId 가 바뀌는 경로는 운영에 존재하지 않는다.</b>
 *
 * <p>그런데 D-010 의 회귀 테스트({@code LicenseIssueEventTest#replayShouldRepublishOwnershipEvent})는
 * <b>새 eventId</b> 로 {@code issue()} 를 부르고 그 주석은 그것을 "운영에서 이벤트 유실을 복구하는
 * 표준 수단"이라고 적어 두었다. 그런 수단은 없다. D-010 이 고친 재발행 로직은 가드 <b>뒤</b>에 있어
 * 실제 복구 경로에서는 도달조차 하지 않는다 — D-021·D-023 과 같은 <b>공허 통과</b>다.
 *
 * <p>고치는 자리는 코드가 아니라 절차다. 가드는 제 일을 하고 있고, 원장과 가드는 같은 트랜잭션에서
 * 쓰이므로 <b>정합적인 백업이라면 둘이 함께 돌아온다.</b> 깨지는 것은 원장만 잃은 경우뿐이고,
 * 그때 필요한 것은 "가드도 같이 지운다"가 적힌 복구 절차다 — {@code docs/runbooks/license-db-loss.md}.
 * 이 테스트는 그 절차의 <b>각 단계가 실제로 필요한지</b>를 고정한다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class LicenseReplayRecoveryTest {

    @Autowired
    LicenseService licenseService;
    @Autowired
    LicenseRepository licenseRepository;
    @Autowired
    ProcessedEventRepository processedEventRepository;
    @Autowired
    OutboxEventRepository outboxEventRepository;
    @Autowired
    JdbcTemplate jdbcTemplate;

    private static List<OrderLine> lines() {
        return List.of(new OrderLine(1L, "게임 1", 1001L, 10_000L, 1));
    }

    /** 원장만 사라진 상태를 만든다 — 백업 복원 실수, 잘못된 DELETE, 부분 마이그레이션. */
    private void loseLedgerOnly(String orderNo) {
        jdbcTemplate.update("delete from license where order_no = ?", orderNo);
    }

    /** 복구 절차의 빠진 단계 — 그 주문을 실어 온 이벤트의 가드 행을 지운다. */
    private void purgeInboxFor(String eventId) {
        jdbcTemplate.update("delete from processed_event where event_id = ? and consumer_group = ?",
                eventId, LicenseService.CONSUMER_GROUP);
    }

    @Test
    @DisplayName("[D-030] 원장만 유실된 뒤 같은 이벤트를 되읽으면 한 건도 복구되지 않는다")
    void replayWithSameEventIdRecoversNothing() {
        String orderNo = "ORD-" + UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        licenseService.issue(eventId, EventType.PAYMENT_COMPLETED, orderNo, 42L, lines());
        assertThat(licenseRepository.findByOrderNo(orderNo)).hasSize(1);

        loseLedgerOnly(orderNo);
        assertThat(licenseRepository.findByOrderNo(orderNo)).isEmpty();

        // 오프셋 리셋·DLT 재투입·Outbox 재적재가 하는 일이 정확히 이것이다 — 같은 eventId 의 재배달.
        licenseService.issue(eventId, EventType.PAYMENT_COMPLETED, orderNo, 42L, lines());

        assertThat(licenseRepository.findByOrderNo(orderNo))
                .as("가드가 막아 재처리가 아무 일도 하지 않았다")
                .isEmpty();
    }

    @Test
    @DisplayName("[D-030] 복구 이벤트가 조용히 사라진다 — 실패도 아니고 로그도 info 다")
    void blockedReplayIsSilent() {
        String orderNo = "ORD-" + UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        licenseService.issue(eventId, EventType.PAYMENT_COMPLETED, orderNo, 42L, lines());
        loseLedgerOnly(orderNo);
        long outboxBefore = outboxEventRepository.count();

        licenseService.issue(eventId, EventType.PAYMENT_COMPLETED, orderNo, 42L, lines());

        // 예외도 없고 이벤트도 없다. 즉 "복구했다" 와 "아무 일도 안 했다" 가 밖에서 구분되지 않는다 —
        // 대량 복구에서 0건 복구를 성공으로 읽게 되는 자리다.
        assertThat(outboxEventRepository.count() - outboxBefore)
                .as("하위 서비스에 알릴 것도 나가지 않는다")
                .isZero();
    }

    @Test
    @DisplayName("[D-030] 가드 행까지 지우면 되읽기가 원장을 되살린다 — 절차의 그 한 줄이 전부다")
    void purgingInboxMakesReplayWork() {
        String orderNo = "ORD-" + UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        licenseService.issue(eventId, EventType.PAYMENT_COMPLETED, orderNo, 42L, lines());
        loseLedgerOnly(orderNo);

        purgeInboxFor(eventId);
        assertThat(processedEventRepository.existsByEventIdAndConsumerGroup(
                eventId, LicenseService.CONSUMER_GROUP)).isFalse();

        licenseService.issue(eventId, EventType.PAYMENT_COMPLETED, orderNo, 42L, lines());

        assertThat(licenseRepository.findByOrderNo(orderNo))
                .as("원장이 되살아난다")
                .hasSize(1);
    }

    /**
     * 복구는 <b>하위 서비스까지</b> 닿아야 끝난다. download 는 자기 원장 없이
     * {@code LicenseIssued} 만으로 권한 사본을 만들기 때문이다(D-010).
     */
    @Test
    @DisplayName("[D-030] 되살아난 복구는 소유 상태 이벤트도 다시 내보낸다 — download 까지 닿는다")
    void recoveredReplayRepublishesOwnership() {
        String orderNo = "ORD-" + UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        licenseService.issue(eventId, EventType.PAYMENT_COMPLETED, orderNo, 42L, lines());
        loseLedgerOnly(orderNo);
        purgeInboxFor(eventId);
        long outboxBefore = outboxEventRepository.count();

        licenseService.issue(eventId, EventType.PAYMENT_COMPLETED, orderNo, 42L, lines());

        assertThat(outboxEventRepository.count() - outboxBefore)
                .as("LicenseIssued 재발행 건수")
                .isEqualTo(1);
    }
}
