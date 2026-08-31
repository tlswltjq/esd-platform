package com.stove.settlement.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.testcontainers.InfraContainers;
import com.stove.settlement.core.domain.RecordType;
import com.stove.settlement.core.domain.SaleType;
import com.stove.settlement.core.domain.SettlementRecord;
import com.stove.settlement.core.domain.SettlementRecordRepository;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 정산 원장 조회 두 갈래 — {@code GET /settlements/orders/{orderNo}} 와
 * {@code GET /settlements/sellers/{sellerId}} 가 타는 경로.
 *
 * <p>판매자별 조회는 <b>어느 테스트도 실행하지 않던 유일한 서비스 메서드</b>였다.
 * 컨트롤러 테스트는 "판매자 ID 가 숫자가 아니면 400" 까지만 보고 서비스에 닿지 못했고,
 * 인수 테스트에도 이 경로가 없었다. 주문별 조회는 인수 테스트에만 있어 기본 빌드 밖이었다.
 *
 * <p>정산 조회에서 틀리면 남의 매출이 섞인다. 그래서 확인할 것은 "나오는가" 가 아니라
 * <b>남의 것이 안 나오는가</b> 다 — 판매자와 월 두 축 모두에서 본다.
 *
 * <p>{@link SettlementCloseTest} 와 같은 이유로 집계 경로를 거치지 않고 원장을 직접 적재한다.
 * 컨테이너를 공유하므로 테스트마다 고유한 주문번호 · 판매자 · 월을 쓴다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class SettlementRecordQueryTest {

    /** 입점 판매 수수료 30% — application.yml 기본값과 같다 */
    private static final BigDecimal PARTNER_FEE_RATE = new BigDecimal("0.3000");

    /** 다른 테스트의 원장과 섞이지 않게 테스트마다 겹치지 않는 월을 준다. */
    private static final AtomicInteger MONTH_SEQ = new AtomicInteger(0);

    @Autowired
    SettlementRecordService settlementRecordService;
    @Autowired
    SettlementRecordRepository recordRepository;

    private static YearMonth uniqueMonth() {
        int seq = MONTH_SEQ.getAndIncrement();
        return YearMonth.of(2800 + seq / 12, seq % 12 + 1);
    }

    private static Long uniqueSeller() {
        return Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000) + 1000;
    }

    private static String uniqueOrderNo() {
        return "ORD-" + UUID.randomUUID();
    }

    private SettlementRecord sale(String orderNo, Long sellerId, long grossAmount, YearMonth month) {
        return recordRepository.save(SettlementRecord.sale(
                orderNo, 1L, sellerId, SaleType.PARTNER, grossAmount, PARTNER_FEE_RATE, month));
    }

    private SettlementRecord refund(SettlementRecord origin, YearMonth month) {
        return recordRepository.save(SettlementRecord.refundOf(origin, month));
    }

    @Test
    @DisplayName("주문번호로 찾으면 그 주문의 매출과 환불이 함께 돌아온다")
    void findsSaleAndRefundOfOneOrder() {
        YearMonth month = uniqueMonth();
        String orderNo = uniqueOrderNo();
        SettlementRecord origin = sale(orderNo, uniqueSeller(), 39_000L, month);
        refund(origin, month);

        List<SettlementRecord> records = settlementRecordService.findByOrder(orderNo);

        assertThat(records).hasSize(2);
        assertThat(records).allMatch(r -> orderNo.equals(r.getOrderNo()));
        assertThat(records).extracting(SettlementRecord::getRecordType)
                .containsExactlyInAnyOrder(RecordType.SALE, RecordType.REFUND);
    }

    @Test
    @DisplayName("원장에 없는 주문번호는 빈 목록이다 — 조회는 예외를 쓰지 않는다")
    void unknownOrderNoReturnsEmpty() {
        assertThat(settlementRecordService.findByOrder(uniqueOrderNo())).isEmpty();
    }

    @Test
    @DisplayName("판매자별 조회에 다른 판매자의 매출이 섞이지 않는다")
    void sellerRecordsExcludeOtherSellers() {
        YearMonth month = uniqueMonth();
        Long mine = uniqueSeller();
        Long other = uniqueSeller();
        sale(uniqueOrderNo(), mine, 39_000L, month);
        sale(uniqueOrderNo(), other, 12_000L, month);

        List<SettlementRecord> records = settlementRecordService.findBySeller(mine, month);

        assertThat(records).hasSize(1);
        assertThat(records).allMatch(r -> mine.equals(r.getSellerId()));
        assertThat(records.get(0).getGrossAmount()).isEqualTo(39_000L);
    }

    @Test
    @DisplayName("판매자별 조회에 다른 달의 매출이 섞이지 않는다")
    void sellerRecordsExcludeOtherMonths() {
        YearMonth asked = uniqueMonth();
        YearMonth neighbour = uniqueMonth();
        Long sellerId = uniqueSeller();
        sale(uniqueOrderNo(), sellerId, 39_000L, asked);
        sale(uniqueOrderNo(), sellerId, 12_000L, neighbour);

        List<SettlementRecord> records = settlementRecordService.findBySeller(sellerId, asked);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).getSettlementMonth()).isEqualTo(asked.toString());
    }

    @Test
    @DisplayName("판매자별 조회는 그 달의 환불도 함께 준다 — 상계 전 금액만 보면 과지급된다")
    void sellerRecordsIncludeRefunds() {
        YearMonth month = uniqueMonth();
        Long sellerId = uniqueSeller();
        SettlementRecord origin = sale(uniqueOrderNo(), sellerId, 39_000L, month);
        refund(origin, month);

        List<SettlementRecord> records = settlementRecordService.findBySeller(sellerId, month);

        assertThat(records).extracting(SettlementRecord::getRecordType)
                .containsExactlyInAnyOrder(RecordType.SALE, RecordType.REFUND);
    }
}
