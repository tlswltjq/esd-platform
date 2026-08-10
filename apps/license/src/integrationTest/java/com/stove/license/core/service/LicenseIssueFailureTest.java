package com.stove.license.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.event.EventType;
import com.stove.common.messaging.outbox.OutboxEvent;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.testcontainers.InfraContainers;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 지급 최종 실패 기록 — <b>Saga 보상의 방아쇠</b>.
 *
 * <p>{@code recordIssueFailure} 는 한 번도 실행된 적이 없었다. 관련 테스트가 전부
 * {@code LicenseService} 를 mock 으로 두고 {@code verify(licenseService).recordIssueFailure(...)}
 * 로 <b>호출됐는지만</b> 확인했기 때문이다. 그래서 이 메서드의 존재 이유인
 * {@code REQUIRES_NEW} 는 검증된 적이 없다.
 *
 * <p>여기서 고정하는 성질은 하나다 — <b>바깥 트랜잭션이 롤백돼도 실패 이벤트는 살아남는다.</b>
 * 이 메서드는 지급 트랜잭션이 깨진 뒤에 불리므로, 같은 트랜잭션에 묶이면 실패 이벤트도 함께
 * 롤백된다. 그러면 payment 는 환불 신호를 영영 받지 못하고
 * <b>결제는 됐는데 게임은 없는</b> 상태가 그대로 굳는다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class LicenseIssueFailureTest {

    @Autowired
    LicenseService licenseService;
    @Autowired
    OutboxEventRepository outboxEventRepository;
    @Autowired
    PlatformTransactionManager transactionManager;

    private List<OutboxEvent> failureEventsFor(String orderNo) {
        return outboxEventRepository.findAll().stream()
                .filter(e -> EventType.LICENSE_ISSUE_FAILED.equals(e.getEventType()))
                .filter(e -> orderNo.equals(e.getAggregateId()))
                .toList();
    }

    @Test
    @DisplayName("실패 기록은 LicenseIssueFailed 를 주문번호 키로 적재한다")
    void recordsFailureEventKeyedByOrder() {
        String orderNo = "ORD-" + UUID.randomUUID();

        licenseService.recordIssueFailure(orderNo, 42L, "재고 없음");

        assertThat(failureEventsFor(orderNo)).hasSize(1);
        OutboxEvent event = failureEventsFor(orderNo).get(0);
        // 파티션 키가 주문번호여야 같은 주문의 이벤트끼리 순서가 보장된다.
        assertThat(event.getPartitionKey()).isEqualTo(orderNo);
        assertThat(event.getPayload()).contains("42").contains("재고 없음");
    }

    @Test
    @DisplayName("[REQUIRES_NEW] 바깥 트랜잭션이 롤백돼도 실패 이벤트는 커밋된 채 남는다")
    void failureEventSurvivesOuterRollback() {
        String orderNo = "ORD-" + UUID.randomUUID();

        // 지급 트랜잭션이 롤백되는 상황을 그대로 만든다.
        new TransactionTemplate(transactionManager).execute(status -> {
            licenseService.recordIssueFailure(orderNo, 42L, "지급 실패");
            status.setRollbackOnly();
            return null;
        });

        // 전파 속성이 REQUIRED 로 바뀌면 이 이벤트도 함께 사라진다 —
        // payment 는 보상 환불을 시작할 수 없고, 사용자는 결제만 되고 게임은 못 받는다.
        assertThat(failureEventsFor(orderNo))
                .as("바깥 롤백에 실패 이벤트가 휩쓸렸다 — 보상 트랜잭션이 시작되지 않는다")
                .hasSize(1);
    }
}
