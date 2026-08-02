package com.stove.license.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.event.EventType;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.test.InfraContainers;
import com.stove.license.core.domain.License;
import com.stove.license.core.domain.LicenseRepository;
import com.stove.license.core.domain.LicenseStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 지급/회수가 <b>무엇을 알리는가</b>. 기존 멱등성 테스트가 "상태가 한 번만 바뀐다"를 보는 반면,
 * 여기서는 그 결과로 나가는 이벤트를 본다.
 *
 * <p>download 는 자기 DB 없이 {@code LicenseIssued} 만으로 권한 사본을 만든다.
 * 즉 이벤트가 나가지 않으면 라이선스는 있는데 다운로드는 안 되는 상태가 굳는다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class LicenseIssueEventTest {

    @Autowired
    LicenseService licenseService;
    @Autowired
    LicenseRepository licenseRepository;
    @Autowired
    OutboxEventRepository outboxEventRepository;

    private static List<OrderLine> lines(Long... productIds) {
        return List.of(productIds).stream()
                .map(id -> new OrderLine(id, "게임 " + id, 1001L, 10_000L, 1))
                .toList();
    }

    private String newOrder() {
        return "ORD-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("지급 성공은 LicenseIssued 를 한 번 발행한다")
    void issuePublishesEventOnce() {
        String orderNo = newOrder();
        long before = outboxEventRepository.count();

        licenseService.issue(UUID.randomUUID().toString(), EventType.PAYMENT_COMPLETED,
                orderNo, 42L, lines(1L, 2L));

        assertThat(outboxEventRepository.count() - before).isEqualTo(1);
        assertThat(licenseRepository.findByOrderNo(orderNo)).hasSize(2);
    }

    @Test
    @DisplayName("같은 상품이 여러 줄로 와도 라이선스는 상품당 한 장이다")
    void duplicateProductLinesIssueSingleLicense() {
        String orderNo = newOrder();

        licenseService.issue(UUID.randomUUID().toString(), EventType.PAYMENT_COMPLETED,
                orderNo, 42L, lines(1L, 1L, 2L));

        assertThat(licenseRepository.findByOrderNo(orderNo)).hasSize(2);
    }

    @Test
    @DisplayName("회수는 LicenseRevoked 를 발행해 download 가 권한을 즉시 거둘 수 있게 한다")
    void revokePublishesEvent() {
        String orderNo = newOrder();
        licenseService.issue(UUID.randomUUID().toString(), EventType.PAYMENT_COMPLETED,
                orderNo, 42L, lines(1L));
        long before = outboxEventRepository.count();

        licenseService.revoke(UUID.randomUUID().toString(), EventType.PAYMENT_CANCELLED,
                orderNo, "USER_REFUND");

        assertThat(outboxEventRepository.count() - before).isEqualTo(1);
        assertThat(licenseRepository.findByOrderNo(orderNo))
                .extracting(License::getStatus).containsOnly(LicenseStatus.REVOKED);
    }

    @Test
    @Tag("known-defect")
    @DisplayName("[D-010] 이미 지급된 주문을 재처리하면 현재 소유 상태를 다시 알려야 한다")
    void replayShouldRepublishOwnershipEvent() {
        String orderNo = newOrder();
        licenseService.issue(UUID.randomUUID().toString(), EventType.PAYMENT_COMPLETED,
                orderNo, 42L, lines(1L));

        long before = outboxEventRepository.count();

        // 다른 eventId 로 재처리 — 운영에서 이벤트 유실을 복구하는 표준 수단이다.
        licenseService.issue(UUID.randomUUID().toString(), EventType.PAYMENT_COMPLETED,
                orderNo, 42L, lines(1L));

        // 기대: 소유 상태를 다시 알려 하위 서비스가 복구할 수 있어야 한다.
        // 실제: 새로 지급된 것이 없으면(issued.isEmpty) 이벤트를 발행하지 않는다.
        //       download 가 최초 LicenseIssued 를 놓쳤다면 재처리로도 복구할 방법이 없고,
        //       사용자는 라이브러리에 게임이 보이는데 다운로드만 안 되는 상태에 갇힌다.
        assertThat(outboxEventRepository.count() - before)
                .as("재처리 시 LicenseIssued 재발행 건수")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("현재 동작: 재처리는 이벤트를 발행하지 않는다")
    void currentBehaviourReplayPublishesNothing() {
        String orderNo = newOrder();
        licenseService.issue(UUID.randomUUID().toString(), EventType.PAYMENT_COMPLETED,
                orderNo, 42L, lines(1L));
        long before = outboxEventRepository.count();

        licenseService.issue(UUID.randomUUID().toString(), EventType.PAYMENT_COMPLETED,
                orderNo, 42L, lines(1L));

        assertThat(outboxEventRepository.count() - before).isZero();
    }

    @Test
    @Tag("known-defect")
    @DisplayName("[D-011] 이미 회수된 주문의 회수 재수신은 이벤트를 다시 발행하지 않아야 한다")
    void repeatedRevokeShouldNotRepublish() {
        String orderNo = newOrder();
        licenseService.issue(UUID.randomUUID().toString(), EventType.PAYMENT_COMPLETED,
                orderNo, 42L, lines(1L));
        licenseService.revoke(UUID.randomUUID().toString(), EventType.PAYMENT_CANCELLED,
                orderNo, "USER_REFUND");

        long before = outboxEventRepository.count();

        // eventId 가 다르면 멱등 가드를 통과한다
        licenseService.revoke(UUID.randomUUID().toString(), EventType.PAYMENT_CANCELLED,
                orderNo, "USER_REFUND");

        // 기대: 상태 변화가 없으면 이벤트도 없다.
        // 실제: 라이선스 목록이 비어 있지 않다는 이유만으로 무조건 재발행한다.
        //       지급 경로는 변화 여부를 따지는데(issued.isEmpty) 회수 경로는 따지지 않는 비대칭.
        assertThat(outboxEventRepository.count() - before)
                .as("변화 없는 회수의 이벤트 발행 건수")
                .isZero();
    }
}
