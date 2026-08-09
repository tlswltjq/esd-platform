package com.stove.settlement.api.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.stove.common.testcontainers.InfraContainers;
import com.stove.settlement.core.domain.SaleType;
import com.stove.settlement.core.domain.SellerSettlement;
import com.stove.settlement.core.domain.SellerSettlementRepository;
import com.stove.settlement.core.domain.SettlementRecord;
import com.stove.settlement.core.domain.SettlementRecordRepository;
import com.stove.settlement.core.port.TaxInvoiceIssuer;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 마감 오케스트레이션 — <b>되돌릴 수 없는 외부 호출</b>(세금계산서 발행)과
 * <b>되돌릴 수 있는 로컬 변경</b>(확정본·원장 close)이 만나는 지점.
 *
 * <p>결제 환불({@code PaymentCancelTest})과 같은 성질을 본다. 정상 경로보다
 * <b>중간에 끊겼을 때 남는 상태</b>가 중요하다 — 국세청에 계산서가 나갔는데
 * 우리 장부에는 근거가 없는 상태가 생기면 안 된다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class SettlementCloseFacadeTest {

    private static final BigDecimal PARTNER_FEE_RATE = new BigDecimal("0.3000");
    private static final AtomicInteger MONTH_SEQ = new AtomicInteger(0);

    @Autowired
    SettlementCloseFacade settlementCloseFacade;
    @Autowired
    SettlementRecordRepository recordRepository;
    @Autowired
    SellerSettlementRepository sellerSettlementRepository;

    @MockitoSpyBean
    TaxInvoiceIssuer taxInvoiceIssuer;

    @AfterEach
    void tearDown() {
        reset(taxInvoiceIssuer);
    }

    /** 다른 테스트의 원장과 섞이지 않도록 고유한 월을 쓴다. 연도까지 굴린다. */
    private static YearMonth uniqueMonth() {
        int seq = MONTH_SEQ.getAndIncrement();
        return YearMonth.of(2800 + seq / 12, seq % 12 + 1);
    }

    private static Long uniqueSeller() {
        return Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000) + 1000;
    }

    private void sale(Long sellerId, long grossAmount, YearMonth month) {
        recordRepository.save(SettlementRecord.sale(
                "ORD-" + UUID.randomUUID(), 1L, sellerId, SaleType.PARTNER,
                grossAmount, PARTNER_FEE_RATE, month));
    }

    private SellerSettlement settlementOf(Long sellerId, YearMonth month) {
        return sellerSettlementRepository
                .findBySellerIdAndSettlementMonth(sellerId, month.toString())
                .orElse(null);
    }

    private List<SettlementRecord> unclosedOf(Long sellerId, YearMonth month) {
        return recordRepository.findBySettlementMonthAndSellerIdAndClosedIsFalse(
                month.toString(), sellerId);
    }

    // ── 정상 경로 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("마감은 확정본을 만들고 세금계산서 번호를 기록한다")
    void closeIssuesAndRecordsTaxInvoice() {
        YearMonth month = uniqueMonth();
        Long seller = uniqueSeller();
        sale(seller, 100_000L, month);

        settlementCloseFacade.closeMonth(month);

        SellerSettlement settlement = settlementOf(seller, month);
        assertThat(settlement).isNotNull();
        assertThat(settlement.getNetAmount()).isEqualTo(70_000L);
        assertThat(settlement.hasTaxInvoice()).isTrue();
        verify(taxInvoiceIssuer).issue(eq(seller), eq(month.toString()), eq(70_000L));
    }

    @Test
    @DisplayName("마감 결과로 확정본이 반환된다")
    void closeReturnsTheSettlement() {
        YearMonth month = uniqueMonth();
        Long seller = uniqueSeller();
        sale(seller, 100_000L, month);

        List<SellerSettlement> closed = settlementCloseFacade.closeMonth(month);

        // 반환값이 비면 운영툴(POST /close)이 무엇이 마감됐는지 보여줄 수 없다.
        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).getSellerId()).isEqualTo(seller);
        assertThat(closed.get(0).getNetAmount()).isEqualTo(70_000L);
        assertThat(closed.get(0).getTaxInvoiceNo()).isNotBlank();
    }

    @Test
    @DisplayName("순액이 정확히 0 인 판매자에게는 계산서를 발행하지 않는다 — 가장 흔한 이월 케이스")
    void exactlyZeroNetIssuesNoInvoice() {
        YearMonth month = uniqueMonth();
        Long seller = uniqueSeller();
        // 매출이 환불로 정확히 상계된다. 경계가 `> 0` 이 아니라 `>= 0` 이 되면
        // 순액 0 원짜리 세금계산서가 나간다.
        SettlementRecord original = SettlementRecord.sale(
                "ORD-" + UUID.randomUUID(), 1L, seller, SaleType.PARTNER,
                100_000L, PARTNER_FEE_RATE, month);
        recordRepository.save(original);
        recordRepository.save(SettlementRecord.refundOf(original, month));

        settlementCloseFacade.closeMonth(month);

        SellerSettlement settlement = settlementOf(seller, month);
        assertThat(settlement.getNetAmount()).isZero();
        assertThat(settlement.hasTaxInvoice()).isFalse();
        verify(taxInvoiceIssuer, never()).issue(eq(seller), anyString(), anyLong());
    }

    @Test
    @DisplayName("순액이 0 이하인 판매자에게는 계산서를 발행하지 않는다")
    void nonPositiveNetIssuesNoInvoice() {
        YearMonth month = uniqueMonth();
        Long seller = uniqueSeller();
        SettlementRecord original = SettlementRecord.sale(
                "ORD-" + UUID.randomUUID(), 1L, seller, SaleType.PARTNER,
                100_000L, PARTNER_FEE_RATE, month);
        recordRepository.save(original);
        recordRepository.save(SettlementRecord.refundOf(original, month));

        settlementCloseFacade.closeMonth(month);

        assertThat(settlementOf(seller, month).hasTaxInvoice()).isFalse();
        verify(taxInvoiceIssuer, never()).issue(eq(seller), anyString(), anyLong());
    }

    // ── [D-022] 발행이 트랜잭션 밖이라는 것 ─────────────────────────────

    @Test
    @DisplayName("[D-022] 발행이 실패해도 확정본과 원장 close 는 남는다")
    void failedIssuanceKeepsTheClosing() {
        YearMonth month = uniqueMonth();
        Long seller = uniqueSeller();
        sale(seller, 100_000L, month);

        doThrow(new IllegalStateException("국세청 연동 장애"))
                .when(taxInvoiceIssuer).issue(eq(seller), anyString(), anyLong());

        // 한 판매자의 발행 실패가 배치 전체를 세우지 않는다.
        assertThatCode(() -> settlementCloseFacade.closeMonth(month)).doesNotThrowAnyException();

        // 확정본은 커밋돼 있어야 한다. 예전에는 마감 전체가 한 트랜잭션이라
        // 여기서 터지면 앞선 판매자들의 확정본까지 함께 롤백됐다.
        SellerSettlement settlement = settlementOf(seller, month);
        assertThat(settlement).isNotNull();
        assertThat(settlement.getNetAmount()).isEqualTo(70_000L);

        // 계산서만 비어 있다 — "마감은 됐고 계산서는 아직"이라는 관측 가능한 상태다.
        assertThat(settlement.hasTaxInvoice()).isFalse();
    }

    @Test
    @DisplayName("[D-022] 발행이 실패했던 판매자는 재실행에서 계산서를 받는다")
    void failedIssuanceIsRetriedOnNextRun() {
        YearMonth month = uniqueMonth();
        Long seller = uniqueSeller();
        sale(seller, 100_000L, month);

        doThrow(new IllegalStateException("국세청 연동 장애"))
                .when(taxInvoiceIssuer).issue(eq(seller), anyString(), anyLong());
        settlementCloseFacade.closeMonth(month);
        reset(taxInvoiceIssuer);

        // 원장은 이미 close 됐으므로 마감 대상에서는 빠진다. 그래도 계산서는 나가야 한다.
        settlementCloseFacade.closeMonth(month);

        assertThat(settlementOf(seller, month).hasTaxInvoice())
                .as("계산서 없는 확정본이 재실행에서 복구되지 않는다")
                .isTrue();
    }

    @Test
    @DisplayName("[D-022] 한 판매자의 실패가 다른 판매자의 마감을 막지 않는다")
    void oneSellerFailureDoesNotBlockOthers() {
        YearMonth month = uniqueMonth();
        Long failing = uniqueSeller();
        Long healthy = uniqueSeller();
        sale(failing, 100_000L, month);
        sale(healthy, 200_000L, month);

        doThrow(new IllegalStateException("국세청 연동 장애"))
                .when(taxInvoiceIssuer).issue(eq(failing), anyString(), anyLong());

        settlementCloseFacade.closeMonth(month);

        // 예전에는 그 달 전체가 한 트랜잭션이라 한 명의 예외가 전량 롤백이었다.
        assertThat(settlementOf(healthy, month)).isNotNull();
        assertThat(settlementOf(healthy, month).hasTaxInvoice()).isTrue();
        assertThat(settlementOf(failing, month)).isNotNull();
    }

    @Test
    @DisplayName("이미 마감된 원장은 다시 집계되지 않는다 — 재실행 안전")
    void rerunDoesNotDoubleCount() {
        YearMonth month = uniqueMonth();
        Long seller = uniqueSeller();
        sale(seller, 100_000L, month);

        settlementCloseFacade.closeMonth(month);
        settlementCloseFacade.closeMonth(month);

        assertThat(settlementOf(seller, month).getNetAmount())
                .as("재실행이 금액을 두 배로 만든다")
                .isEqualTo(70_000L);
        assertThat(unclosedOf(seller, month)).isEmpty();
        // 발행은 첫 실행 한 번뿐이다.
        verify(taxInvoiceIssuer, times(1)).issue(eq(seller), anyString(), anyLong());
    }

    @Test
    @DisplayName("지각 원장은 확정본에 더해진다 — 마감 후 도착분이 유실되지 않는다")
    void lateRecordsAreAccumulated() {
        YearMonth month = uniqueMonth();
        Long seller = uniqueSeller();
        sale(seller, 100_000L, month);
        settlementCloseFacade.closeMonth(month);

        sale(seller, 50_000L, month);
        settlementCloseFacade.closeMonth(month);

        assertThat(settlementOf(seller, month).getNetAmount()).isEqualTo(70_000L + 35_000L);
        assertThat(unclosedOf(seller, month)).isEmpty();
    }
}
