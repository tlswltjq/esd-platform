package com.stove.settlement.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.test.InfraContainers;
import com.stove.settlement.core.domain.SaleType;
import com.stove.settlement.core.domain.SellerSettlement;
import com.stove.settlement.core.domain.SellerSettlementRepository;
import com.stove.settlement.core.domain.SettlementRecord;
import com.stove.settlement.core.domain.SettlementRecordRepository;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 월 마감. 정산에서 가장 위험한 연산이다 — 여기서 빠진 금액은 판매자에게 영영 지급되지 않는다.
 *
 * <p>집계 경로({@code recordSale})를 거치지 않고 원장을 직접 적재한다. 두 가지 이유다.
 * <ul>
 *   <li>{@code recordSale} 은 {@code LocalDate.now()} 로 귀속 월을 정해 테스트가 월을 고를 수 없다</li>
 *   <li>같은 컨테이너를 공유하는 다른 테스트의 원장과 섞이면 마감 대상이 오염된다 —
 *       테스트마다 고유한 월·판매자를 쓰면 마감 로직만 독립적으로 관찰할 수 있다</li>
 * </ul>
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class SettlementCloseTest {

    /** 입점 판매 수수료 30% — application.yml 기본값과 같다 */
    private static final BigDecimal PARTNER_FEE_RATE = new BigDecimal("0.3000");

    /** 테스트마다 겹치지 않는 마감 월을 준다. 다른 테스트의 원장과 섞이지 않게 하기 위함이다. */
    private static final AtomicInteger MONTH_SEQ = new AtomicInteger(1);

    @Autowired
    SettlementService settlementService;
    @Autowired
    SettlementRecordRepository recordRepository;
    @Autowired
    SellerSettlementRepository sellerSettlementRepository;

    private static YearMonth uniqueMonth() {
        return YearMonth.of(2999, MONTH_SEQ.getAndIncrement());
    }

    private static Long uniqueSeller() {
        return Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000) + 1000;
    }

    private SettlementRecord sale(Long sellerId, long grossAmount, YearMonth month) {
        return recordRepository.save(SettlementRecord.sale(
                "ORD-" + UUID.randomUUID(), 1L, sellerId, SaleType.PARTNER,
                grossAmount, PARTNER_FEE_RATE, month));
    }

    private SettlementRecord refund(Long sellerId, long grossAmount, YearMonth month) {
        return recordRepository.save(SettlementRecord.refundOf(
                SettlementRecord.sale("ORD-" + UUID.randomUUID(), 1L, sellerId, SaleType.PARTNER,
                        grossAmount, PARTNER_FEE_RATE, month),
                month));
    }

    @Test
    @DisplayName("마감은 판매자별로 원장을 합산해 확정본을 만든다")
    void closesBySeller() {
        YearMonth month = uniqueMonth();
        Long seller = uniqueSeller();
        sale(seller, 100_000L, month);
        sale(seller, 50_000L, month);

        List<SellerSettlement> closed = settlementService.closeMonth(month);

        assertThat(closed).hasSize(1);
        SellerSettlement settlement = closed.get(0);
        assertThat(settlement.getSellerId()).isEqualTo(seller);
        assertThat(settlement.getGrossAmount()).isEqualTo(150_000L);
        assertThat(settlement.getFeeAmount()).isEqualTo(45_000L);      // 30%
        assertThat(settlement.getNetAmount()).isEqualTo(105_000L);
        assertThat(settlement.getRecordCount()).isEqualTo(2);
        assertThat(settlement.getTaxInvoiceNo()).isNotBlank();
    }

    @Test
    @DisplayName("마감한 원장은 closed 로 표시되어 다음 마감 대상에서 빠진다")
    void closedRecordsAreExcludedNextTime() {
        YearMonth month = uniqueMonth();
        Long seller = uniqueSeller();
        sale(seller, 100_000L, month);

        settlementService.closeMonth(month);

        assertThat(recordRepository.findBySettlementMonthAndClosedIsFalse(month.toString())).isEmpty();
        assertThat(settlementService.closeMonth(month)).isEmpty();
    }

    @Test
    @DisplayName("같은 달을 두 번 마감해도 확정본은 한 벌만 남는다")
    void repeatedCloseDoesNotDuplicateSettlement() {
        YearMonth month = uniqueMonth();
        Long seller = uniqueSeller();
        sale(seller, 100_000L, month);

        settlementService.closeMonth(month);
        settlementService.closeMonth(month);

        assertThat(sellerSettlementRepository.findBySellerIdAndSettlementMonth(seller, month.toString()))
                .isPresent();
        assertThat(settlementService.getClosedSettlements(month)
                .stream().filter(s -> s.getSellerId().equals(seller)).toList()).hasSize(1);
    }

    @Test
    @DisplayName("환불이 매출을 넘겨 순액이 음수면 세금계산서를 발행하지 않는다")
    void negativeNetIssuesNoInvoice() {
        YearMonth month = uniqueMonth();
        Long seller = uniqueSeller();
        sale(seller, 50_000L, month);
        refund(seller, 100_000L, month);

        List<SellerSettlement> closed = settlementService.closeMonth(month);

        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).getNetAmount()).isNegative();
        assertThat(closed.get(0).getTaxInvoiceNo()).isNull();
    }

    @Test
    @DisplayName("확정본 합계는 원장 합계와 일치한다")
    void settlementMatchesLedger() {
        YearMonth month = uniqueMonth();
        Long seller = uniqueSeller();
        sale(seller, 100_000L, month);
        sale(seller, 30_000L, month);
        refund(seller, 30_000L, month);

        settlementService.closeMonth(month);

        long ledgerNet = recordRepository.findBySellerIdAndSettlementMonth(seller, month.toString())
                .stream().mapToLong(SettlementRecord::getNetAmount).sum();
        long settledNet = sellerSettlementRepository
                .findBySellerIdAndSettlementMonth(seller, month.toString())
                .orElseThrow().getNetAmount();

        assertThat(settledNet).isEqualTo(ledgerNet);
    }

    @Test
    @Tag("known-defect")
    @DisplayName("[D-001] 마감 후 도착한 원장은 다음 마감에서 확정본에 반영되어야 한다")
    void lateRecordShouldBeSettledOnNextClose() {
        YearMonth month = uniqueMonth();
        Long seller = uniqueSeller();
        sale(seller, 100_000L, month);

        settlementService.closeMonth(month);

        // 지각 도착한 매출. 이벤트 재전송·수동 보정·월경계 지연으로 실제로 흔하다.
        sale(seller, 50_000L, month);
        settlementService.closeMonth(month);

        long ledgerNet = recordRepository.findBySellerIdAndSettlementMonth(seller, month.toString())
                .stream().mapToLong(SettlementRecord::getNetAmount).sum();
        long settledNet = settlementService.getClosedSettlements(month).stream()
                .filter(s -> s.getSellerId().equals(seller))
                .mapToLong(SellerSettlement::getNetAmount).sum();

        // 기대: 원장 합계(105,000) == 확정본 합계
        // 실제: 확정본은 첫 마감분(70,000)에 머문다. 차액 35,000 은 어디에도 없다.
        assertThat(settledNet).as("판매자에게 지급될 금액").isEqualTo(ledgerNet);
    }

    @Test
    @Tag("known-defect")
    @DisplayName("[D-001] 확정본에 반영되지 않은 원장은 closed 로 표시하면 안 된다")
    void unsettledRecordShouldStayOpen() {
        YearMonth month = uniqueMonth();
        Long seller = uniqueSeller();
        sale(seller, 100_000L, month);
        settlementService.closeMonth(month);

        SettlementRecord late = sale(seller, 50_000L, month);
        settlementService.closeMonth(month);

        SettlementRecord reloaded = recordRepository.findById(late.getId()).orElseThrow();

        // closeMonth 는 이미 확정본이 있는 판매자를 집계에서 제외하면서도
        // 조회된 원장 전체에 close() 를 찍는다. 그래서 이 원장은
        // '처리 완료'로 표시된 채 어떤 확정본에도 속하지 않는다 — 재실행으로도 복구 불가.
        assertThat(reloaded.isClosed())
                .as("집계되지 않은 원장의 마감 표시")
                .isFalse();
    }
}
