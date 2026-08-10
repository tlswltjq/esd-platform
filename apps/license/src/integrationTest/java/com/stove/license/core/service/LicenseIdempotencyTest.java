package com.stove.license.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.event.EventType;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.messaging.inbox.ProcessedEventRepository;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.license.core.domain.License;
import com.stove.license.core.domain.LicenseRepository;
import com.stove.license.core.domain.LicenseStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 라이선스 지급의 멱등성.
 *
 * <p>리스너를 거치지 않고 서비스를 직접 부른다 — 트랜잭션과 멱등 가드를 어댑터에서
 * 서비스로 옮긴 덕분에 Kafka 없이도 이 성질을 검증할 수 있다.
 *
 * <p>Outbox 릴레이는 꺼둔다. 켜두면 배경 스레드가 적재된 이벤트를 발행하며 상태를 바꿔
 * 발행 건수를 세는 검증이 흔들린다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class LicenseIdempotencyTest {

    private static final String GROUP = "license";

    @Autowired
    LicenseService licenseService;
    @Autowired
    LicenseRepository licenseRepository;
    @Autowired
    ProcessedEventRepository processedEventRepository;
    @Autowired
    OutboxEventRepository outboxEventRepository;

    private static List<OrderLine> lines(Long... productIds) {
        return List.of(productIds).stream()
                .map(id -> new OrderLine(id, "게임 " + id, 1001L, 10_000L, 1))
                .toList();
    }

    @Test
    @DisplayName("같은 이벤트가 두 번 와도 라이선스는 한 번만 지급된다")
    void sameEventDeliveredTwiceIssuesOnce() {
        String eventId = UUID.randomUUID().toString();
        String orderNo = "ORD-" + UUID.randomUUID();
        long outboxBefore = outboxEventRepository.count();

        licenseService.issue(eventId, EventType.PAYMENT_COMPLETED, orderNo, 42L, lines(1L, 2L));
        licenseService.issue(eventId, EventType.PAYMENT_COMPLETED, orderNo, 42L, lines(1L, 2L));

        assertThat(licenseRepository.findByOrderNo(orderNo)).hasSize(2);
        assertThat(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, GROUP)).isTrue();

        // 두 번째 호출은 가드에서 끊기므로 LicenseIssued 도 한 번만 적재된다
        assertThat(outboxEventRepository.count() - outboxBefore).isEqualTo(1);
    }

    @Test
    @DisplayName("가드를 지나쳐도 도메인 유니크가 중복 지급을 막는다")
    void differentEventForSameOrderDoesNotDoubleIssue() {
        String orderNo = "ORD-" + UUID.randomUUID();

        // eventId 가 다르면 Inbox 가드는 통과한다 — 이벤트 재발급, 수동 재처리 같은 상황
        licenseService.issue(UUID.randomUUID().toString(), EventType.PAYMENT_COMPLETED,
                orderNo, 42L, lines(1L));
        licenseService.issue(UUID.randomUUID().toString(), EventType.PAYMENT_COMPLETED,
                orderNo, 42L, lines(1L));

        // 그래도 (order_no, product_id) 유니크와 존재 확인이 두 번째 지급을 걸러낸다
        assertThat(licenseRepository.findByOrderNo(orderNo)).hasSize(1);
    }

    @Test
    @DisplayName("회수도 중복 수신에 안전하다")
    void revokeIsIdempotent() {
        String orderNo = "ORD-" + UUID.randomUUID();
        licenseService.issue(UUID.randomUUID().toString(), EventType.PAYMENT_COMPLETED,
                orderNo, 42L, lines(1L));

        String revokeEventId = UUID.randomUUID().toString();
        licenseService.revoke(revokeEventId, EventType.PAYMENT_CANCELLED, orderNo, "USER_REFUND");
        licenseService.revoke(revokeEventId, EventType.PAYMENT_CANCELLED, orderNo, "USER_REFUND");

        List<License> licenses = licenseRepository.findByOrderNo(orderNo);
        assertThat(licenses).hasSize(1);
        assertThat(licenses.get(0).getStatus()).isEqualTo(LicenseStatus.REVOKED);
    }
}
