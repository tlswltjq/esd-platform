package com.stove.settlement.core.service;

import com.stove.common.event.payload.OrderLine;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.settlement.core.domain.FeePolicy;
import com.stove.settlement.core.domain.RecordType;
import com.stove.settlement.core.domain.SaleType;
import com.stove.settlement.core.domain.SellerSettlement;
import com.stove.settlement.core.domain.SellerSettlementRepository;
import com.stove.settlement.core.domain.SettlementRecord;
import com.stove.settlement.core.domain.SettlementRecordRepository;
import com.stove.settlement.core.port.TaxInvoiceIssuer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정산 도메인. 오픈마켓 구조상 가장 복잡한 영역이라 규칙을 세 조각으로 분리했다.
 * <ul>
 *   <li>집계(매출/환불 원장 적재) — 이벤트 기반, 멱등</li>
 *   <li>수수료 계산 — {@link FeePolicy}</li>
 *   <li>마감(월별 확정 + 세금계산서) — 배치</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SettlementService {

    private static final String CONSUMER_GROUP = "settlement";

    private final SettlementRecordRepository recordRepository;
    private final SellerSettlementRepository sellerSettlementRepository;
    private final FeePolicy feePolicy;
    private final ProcessedEventGuard processedEventGuard;

    /**
     * [결제] payment → PaymentCompleted → settlement (매출 집계)
     *
     * <p>금전 원장이므로 Inbox 멱등 가드와 원장 유니크 제약을 <b>둘 다</b> 건다.
     * 가드 마킹은 원장 적재와 같은 커밋이어야 한다.
     */
    public void recordSale(String eventId, String eventType, String orderNo, List<OrderLine> lines) {
        if (!processedEventGuard.firstDelivery(eventId, CONSUMER_GROUP, eventType)) {
            return;
        }
        YearMonth month = YearMonth.from(LocalDate.now());

        for (OrderLine line : lines) {
            if (recordRepository.existsByOrderNoAndProductIdAndRecordType(
                    orderNo, line.productId(), RecordType.SALE)) {
                continue; // 이벤트 중복 수신
            }
            SaleType saleType = feePolicy.saleTypeOf(line.sellerId());
            BigDecimal feeRate = feePolicy.feeRateOf(saleType);

            recordRepository.save(SettlementRecord.sale(orderNo, line.productId(), line.sellerId(),
                    saleType, line.lineAmount(), feeRate, month));
        }
        log.info("매출 집계 orderNo={} lines={}", orderNo, lines.size());
    }

    /**
     * [환불] payment → PaymentCancelled → settlement (역산).
     *
     * <p>환불 이벤트에는 항목 정보가 없다. 자기 원장의 SALE 레코드를 근거로 부호를 뒤집어 상계하므로
     * 다른 서비스에 되묻지 않고도 정확한 역산이 가능하다.
     */
    public void recordRefund(String eventId, String eventType, String orderNo) {
        if (!processedEventGuard.firstDelivery(eventId, CONSUMER_GROUP, eventType)) {
            return;
        }
        List<SettlementRecord> sales = recordRepository.findByOrderNoAndRecordType(orderNo, RecordType.SALE);
        if (sales.isEmpty()) {
            log.warn("역산할 매출 원장이 없음 orderNo={}", orderNo);
            return;
        }
        YearMonth month = YearMonth.from(LocalDate.now());

        for (SettlementRecord sale : sales) {
            if (recordRepository.existsByOrderNoAndProductIdAndRecordType(
                    orderNo, sale.getProductId(), RecordType.REFUND)) {
                continue;
            }
            recordRepository.save(SettlementRecord.refundOf(sale, month));
        }
        log.info("환불 역산 orderNo={} records={}", orderNo, sales.size());
    }

    /**
     * 이번 달 마감 대상 판매자.
     *
     * <p>마감은 판매자 단위 트랜잭션으로 쪼개져 있으므로
     * ({@link com.stove.settlement.api.application.SettlementCloseFacade} 참고)
     * 오케스트레이터가 먼저 대상만 읽는다.
     */
    @Transactional(readOnly = true)
    public List<Long> sellersToClose(YearMonth month) {
        String monthKey = month.toString();

        // 두 부류를 합친다.
        //  1. 미마감 원장이 있는 판매자 — 보통의 마감 대상
        //  2. 마감은 끝났는데 계산서가 없는 판매자 — 발행이 실패했던 건
        //
        // 2번을 빼면 발행 실패가 영구 방치된다. 원장이 이미 close 되어 1번 기준으로는
        // 잡히지 않기 때문이다. 발행이 트랜잭션 밖으로 나오면서 생긴 새 경로다.
        Stream<Long> withOpenRecords = recordRepository.findSellerIdsToClose(monthKey).stream();
        Stream<Long> awaitingInvoice = sellerSettlementRepository.findAwaitingTaxInvoice(monthKey)
                .stream()
                .map(SellerSettlement::getSellerId);

        return Stream.concat(withOpenRecords, awaitingInvoice).distinct().sorted().toList();
    }

    /** 이미 마감된 확정본. 발행만 남은 판매자를 조율 계층이 집어갈 때 쓴다. */
    @Transactional(readOnly = true)
    public SellerSettlement getSettlement(Long sellerId, YearMonth month) {
        return sellerSettlementRepository
                .findBySellerIdAndSettlementMonth(sellerId, month.toString())
                .orElse(null);
    }

    /**
     * 판매자 한 명의 마감을 <b>독립 트랜잭션</b>으로 확정한다.
     *
     * <p>세금계산서는 여기서 발행하지 않는다. 발행은 되돌릴 수 없는 외부 호출이라
     * 트랜잭션 안에 들어오면 뒤가 깨졌을 때 <b>장부에는 없는 계산서</b>가 남는다 —
     * 결제 쪽 [D-006] 과 같은 모양이고, 여기서는 [D-022] 였다.
     *
     * <p>확정본을 먼저 커밋하고, 발행은 조율 계층이 커밋 뒤에 한다. 중간에 멈추면
     * "마감은 됐고 계산서는 아직"이라는 관측 가능한 상태가 남아 재시도 대상이 된다.
     *
     * <p>미마감 원장은 예외 없이 전부 확정본에 반영한다. 반영 대상과 close 대상이 어긋나면
     * "마감됐는데 어디에도 없는 금액"이 생긴다.
     *
     * @return 확정본. 마감할 원장이 없으면 {@code null}
     */
    public SellerSettlement closeSeller(Long sellerId, YearMonth month) {
        String monthKey = month.toString();
        List<SettlementRecord> records =
                recordRepository.findBySettlementMonthAndSellerIdAndClosedIsFalse(monthKey, sellerId);
        if (records.isEmpty()) {
            return null;
        }

        long gross = records.stream().mapToLong(SettlementRecord::getGrossAmount).sum();
        long fee = records.stream().mapToLong(SettlementRecord::getFeeAmount).sum();
        long net = records.stream().mapToLong(SettlementRecord::getNetAmount).sum();

        SellerSettlement settlement = sellerSettlementRepository
                .findBySellerIdAndSettlementMonth(sellerId, monthKey)
                .map(existing -> revise(existing, gross, fee, net, records.size(), sellerId, monthKey))
                .orElseGet(() -> SellerSettlement.close(
                        sellerId, monthKey, gross, fee, net, records.size(), null));

        records.forEach(SettlementRecord::close);
        return sellerSettlementRepository.save(settlement);
    }

    /**
     * 발행이 끝난 계산서 번호를 확정본에 기록한다(마감 2단계).
     *
     * <p>이 커밋이 깨지면 계산서는 나갔는데 번호가 안 남는다. 그래서
     * {@link TaxInvoiceIssuer#issue} 는 {@code (sellerId, month)} 기준 멱등이어야 하고,
     * 재시도가 이중 발행이 되지 않는다.
     */
    public void assignTaxInvoice(Long sellerId, YearMonth month, String taxInvoiceNo) {
        sellerSettlementRepository.findBySellerIdAndSettlementMonth(sellerId, month.toString())
                .ifPresent(settlement -> settlement.assignTaxInvoice(taxInvoiceNo));
    }

    private SellerSettlement revise(SellerSettlement existing, long gross, long fee, long net,
                                    int recordCount, Long sellerId, String monthKey) {
        existing.accumulate(gross, fee, net, recordCount);

        if (existing.hasTaxInvoice()) {
            // 이미 발행된 계산서의 금액이 바뀌었다. 실제 운영에서는 수정세금계산서가 필요한 건이라
            // 조용히 넘기지 않고 남긴다.
            log.warn("마감 확정본 금액 변경 — 수정세금계산서 검토 필요 sellerId={} month={} 추가액={} 계산서={}",
                    sellerId, monthKey, net, existing.getTaxInvoiceNo());
        }
        // 발행을 미뤘던 판매자(순액 0 이하)가 지각 매출로 양수가 되는 경우는
        // needsTaxInvoice() 가 참이 되므로 조율 계층이 커밋 뒤에 발행한다.
        return existing;
    }

    @Transactional(readOnly = true)
    public List<SettlementRecord> getRecords(String orderNo) {
        return recordRepository.findByOrderNo(orderNo);
    }

    @Transactional(readOnly = true)
    public List<SettlementRecord> getSellerRecords(Long sellerId, YearMonth month) {
        return recordRepository.findBySellerIdAndSettlementMonth(sellerId, month.toString());
    }

    @Transactional(readOnly = true)
    public List<SellerSettlement> getClosedSettlements(YearMonth month) {
        return sellerSettlementRepository.findBySettlementMonth(month.toString());
    }
}
