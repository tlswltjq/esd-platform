package com.stove.settlement.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.event.EventType;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.messaging.inbox.ProcessedEventRepository;
import com.stove.common.test.InfraContainers;
import com.stove.settlement.core.domain.RecordType;
import com.stove.settlement.core.domain.SettlementRecord;
import com.stove.settlement.core.domain.SettlementRecordRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 정산 집계의 멱등성. 금전 원장이라 이 서비스에서 중복은 곧 사고다.
 *
 * <p>방어가 두 겹인 이유를 그대로 검증한다 — Inbox 가드는 <b>같은 메시지</b>를,
 * 도메인 유니크는 <b>경위가 어떻든 같은 결과</b>를 막는다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class SettlementIdempotencyTest {

    private static final String GROUP = "settlement";

    @Autowired
    SettlementService settlementService;
    @Autowired
    SettlementRecordRepository recordRepository;
    @Autowired
    ProcessedEventRepository processedEventRepository;

    private static List<OrderLine> lines() {
        return List.of(
                new OrderLine(1L, "게임 A", 1001L, 30_000L, 1),
                new OrderLine(2L, "게임 B", 1002L, 20_000L, 2));
    }

    @Test
    @DisplayName("같은 결제 이벤트가 두 번 와도 매출 원장은 한 벌만 쌓인다")
    void sameEventDeliveredTwiceRecordsOnce() {
        String eventId = UUID.randomUUID().toString();
        String orderNo = "ORD-" + UUID.randomUUID();

        settlementService.recordSale(eventId, EventType.PAYMENT_COMPLETED, orderNo, lines());
        settlementService.recordSale(eventId, EventType.PAYMENT_COMPLETED, orderNo, lines());

        assertThat(recordRepository.findByOrderNoAndRecordType(orderNo, RecordType.SALE)).hasSize(2);
        assertThat(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, GROUP)).isTrue();
    }

    @Test
    @DisplayName("가드를 지나쳐도 원장 유니크가 중복 집계를 막는다")
    void differentEventForSameOrderDoesNotDoubleCount() {
        String orderNo = "ORD-" + UUID.randomUUID();

        settlementService.recordSale(UUID.randomUUID().toString(), EventType.PAYMENT_COMPLETED,
                orderNo, lines());
        settlementService.recordSale(UUID.randomUUID().toString(), EventType.PAYMENT_COMPLETED,
                orderNo, lines());

        List<SettlementRecord> sales = recordRepository.findByOrderNoAndRecordType(orderNo, RecordType.SALE);
        assertThat(sales).hasSize(2);
        assertThat(sales.stream().mapToLong(SettlementRecord::getGrossAmount).sum()).isEqualTo(70_000L);
    }

    @Test
    @DisplayName("환불 역산도 중복 수신에 안전하다")
    void refundIsIdempotent() {
        String orderNo = "ORD-" + UUID.randomUUID();
        settlementService.recordSale(UUID.randomUUID().toString(), EventType.PAYMENT_COMPLETED,
                orderNo, lines());

        String refundEventId = UUID.randomUUID().toString();
        settlementService.recordRefund(refundEventId, EventType.PAYMENT_CANCELLED, orderNo);
        settlementService.recordRefund(refundEventId, EventType.PAYMENT_CANCELLED, orderNo);

        List<SettlementRecord> refunds = recordRepository.findByOrderNoAndRecordType(orderNo, RecordType.REFUND);
        assertThat(refunds).hasSize(2);

        // 매출과 환불이 상계되어 순액 합계가 0
        assertThat(recordRepository.findByOrderNo(orderNo).stream()
                .mapToLong(SettlementRecord::getNetAmount).sum()).isZero();
    }
}
