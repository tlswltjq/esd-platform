package com.stove.license.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventType;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.license.core.domain.License;
import com.stove.license.core.domain.LicenseRepository;
import com.stove.license.core.domain.LicenseStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
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

    /**
     * 적재된 이벤트의 페이로드를 읽는다.
     *
     * <p>{@code count()} 차이만 보는 단언은 <b>잘못된 이벤트를 발행해도 똑같이 1</b>이다.
     * 무엇이 실렸는지는 페이로드를 읽어야 알 수 있다.
     *
     * <p>문자열로 대조하지 않고 파싱한다 — 직렬화 설정(공백·필드 순서)이 바뀌었을 뿐인데
     * 깨지는 단언은 계약을 지키는 것이 아니라 표현을 고정하는 것이다.
     */
    private JsonNode latestPayloadOf(String eventType, String orderNo) {
        String payload = outboxEventRepository.findAll().stream()
                .filter(event -> eventType.equals(event.getEventType()))
                .filter(event -> orderNo.equals(event.getAggregateId()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError(
                        "적재된 이벤트가 없다: type=%s orderNo=%s".formatted(eventType, orderNo)))
                .getPayload();
        try {
            return new ObjectMapper().readTree(payload);
        } catch (Exception e) {
            throw new AssertionError("페이로드를 읽을 수 없다: " + payload, e);
        }
    }

    private static List<Long> productIdsOf(JsonNode payload) {
        List<Long> ids = new ArrayList<>();
        payload.get("productIds").forEach(node -> ids.add(node.asLong()));
        return ids;
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
    @DisplayName("[D-010] 이미 지급된 주문을 재처리하면 현재 소유 상태를 다시 알린다")
    void replayShouldRepublishOwnershipEvent() {
        String orderNo = newOrder();
        licenseService.issue(UUID.randomUUID().toString(), EventType.PAYMENT_COMPLETED,
                orderNo, 42L, lines(1L));

        long before = outboxEventRepository.count();

        // 다른 eventId 로 재처리 — payment 가 같은 주문의 결제 완료를 새 이벤트로 다시 알린 경우다.
        //
        // 예전 주석은 이것을 "운영에서 이벤트 유실을 복구하는 표준 수단" 이라고 적었는데 **틀렸다.**
        // 운영의 재처리 수단(오프셋 리셋 · DLT 재투입 · Outbox 재적재)은 셋 다 eventId 를 보존하고,
        // 그러면 아래 로직에 닿기 전에 Inbox 가드가 먼저 막는다. 즉 이 테스트가 지키는 재발행 동작은
        // **실제 복구 경로에서는 도달하지 않는다** — 그 사실을 재현한 것이 D-030 이고,
        // 같은 eventId 쪽은 LicenseReplayRecoveryTest 가 맡는다.
        licenseService.issue(UUID.randomUUID().toString(), EventType.PAYMENT_COMPLETED,
                orderNo, 42L, lines(1L));

        // 수정 전에는 새로 지급된 것이 없으면 이벤트를 발행하지 않았다.
        // download 가 최초 LicenseIssued 를 놓쳤다면 재처리로도 복구할 방법이 없었고,
        // 사용자는 라이브러리에 게임이 보이는데 다운로드만 안 되는 상태에 갇혔다.
        assertThat(outboxEventRepository.count() - before)
                .as("재처리 시 LicenseIssued 재발행 건수")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("[D-010] 재발행되는 이벤트는 '변화'가 아니라 '현재 소유 상태'를 싣는다")
    void republishedEventCarriesFullOwnership() {
        String orderNo = newOrder();
        // 1번만 먼저 지급된 상태를 만든다.
        licenseService.issue(UUID.randomUUID().toString(), EventType.PAYMENT_COMPLETED,
                orderNo, 42L, lines(1L));

        // 재처리로 2번이 추가된다. 이때 '변화'는 2번뿐이지만, 이벤트는 현재 소유 상태 전체를
        // 실어야 한다 — download 는 이 한 건으로 권한 사본을 복구하므로 2번만 실리면 1번을 잃는다.
        licenseService.issue(UUID.randomUUID().toString(), EventType.PAYMENT_COMPLETED,
                orderNo, 42L, lines(1L, 2L));

        // 이 테스트는 이름과 달리 저장소 크기만 보고 있었다. 크기는 '변화'만 실어도 2다 —
        // 이벤트 페이로드를 읽지 않으면 D-010 이 되돌아가도 통과한다.
        assertThat(productIdsOf(latestPayloadOf(EventType.LICENSE_ISSUED, orderNo)))
                .as("재발행 이벤트에 현재 소유 상태 전체가 실려야 한다")
                .containsExactlyInAnyOrder(1L, 2L);
        assertThat(licenseRepository.findByOrderNo(orderNo)).hasSize(2);
    }

    @Test
    @DisplayName("회수 이벤트에는 실제로 회수된 상품만 실린다")
    void revokedEventCarriesOnlyWhatChanged() {
        String orderNo = newOrder();
        licenseService.issue(UUID.randomUUID().toString(), EventType.PAYMENT_COMPLETED,
                orderNo, 42L, lines(1L, 2L));

        licenseService.revoke(UUID.randomUUID().toString(), EventType.PAYMENT_CANCELLED,
                orderNo, "USER_REFUND");

        // 회수는 '변화'를 싣는다(지급과 반대다). 회수되지 않은 상품이 섞여 들어가면
        // download 가 멀쩡한 권한까지 거둔다.
        JsonNode payload = latestPayloadOf(EventType.LICENSE_REVOKED, orderNo);
        assertThat(productIdsOf(payload)).containsExactlyInAnyOrder(1L, 2L);
        assertThat(payload.get("reason").asText()).isEqualTo("USER_REFUND");
    }

    @Test
    @DisplayName("[D-010] 이미 전부 회수된 주문에는 지급 이벤트를 발행하지 않는다")
    void revokedOrderDoesNotRepublishOwnership() {
        String orderNo = newOrder();
        licenseService.issue(UUID.randomUUID().toString(), EventType.PAYMENT_COMPLETED,
                orderNo, 42L, lines(1L));
        licenseService.revoke(UUID.randomUUID().toString(), EventType.PAYMENT_CANCELLED,
                orderNo, "USER_REFUND");
        long before = outboxEventRepository.count();

        // 지각 도착한 결제 완료 이벤트. 소유하지 않은 상태를 '지급'으로 알릴 수는 없다.
        licenseService.issue(UUID.randomUUID().toString(), EventType.PAYMENT_COMPLETED,
                orderNo, 42L, lines(1L));

        assertThat(outboxEventRepository.count() - before).isZero();
    }

    @Test
    @DisplayName("[D-011] 이미 회수된 주문의 회수 재수신은 이벤트를 다시 발행하지 않는다")
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

        // 상태 변화가 없으면 이벤트도 없다.
        // 수정 전에는 라이선스 목록이 비어 있지 않다는 이유만으로 무조건 재발행했다.
        assertThat(outboxEventRepository.count() - before)
                .as("변화 없는 회수의 이벤트 발행 건수")
                .isZero();
    }
}
