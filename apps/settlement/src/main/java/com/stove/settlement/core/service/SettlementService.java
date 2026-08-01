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
import java.util.Map;
import java.util.stream.Collectors;
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
    private final TaxInvoiceIssuer taxInvoiceIssuer;
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
     * 월 마감. 미마감 원장을 판매자별로 합산해 확정본을 만들고 세금계산서를 발행한다.
     * 이미 마감된 월은 다시 마감하지 않는다(재실행 안전).
     */
    public List<SellerSettlement> closeMonth(YearMonth month) {
        String monthKey = month.toString();
        List<SettlementRecord> targets = recordRepository.findBySettlementMonthAndClosedIsFalse(monthKey);
        if (targets.isEmpty()) {
            log.info("마감 대상 없음 month={}", monthKey);
            return List.of();
        }

        Map<Long, List<SettlementRecord>> bySeller = targets.stream()
                .collect(Collectors.groupingBy(SettlementRecord::getSellerId));

        List<SellerSettlement> closed = bySeller.entrySet().stream()
                .filter(entry -> sellerSettlementRepository
                        .findBySellerIdAndSettlementMonth(entry.getKey(), monthKey).isEmpty())
                .map(entry -> closeSeller(entry.getKey(), monthKey, entry.getValue()))
                .toList();

        targets.forEach(SettlementRecord::close);
        log.info("정산 마감 month={} sellers={} records={}", monthKey, closed.size(), targets.size());
        return closed;
    }

    private SellerSettlement closeSeller(Long sellerId, String monthKey, List<SettlementRecord> records) {
        long gross = records.stream().mapToLong(SettlementRecord::getGrossAmount).sum();
        long fee = records.stream().mapToLong(SettlementRecord::getFeeAmount).sum();
        long net = records.stream().mapToLong(SettlementRecord::getNetAmount).sum();

        // 환불이 매출을 초과해 음수가 된 판매자는 세금계산서를 발행하지 않고 이월한다
        String invoiceNo = net > 0 ? taxInvoiceIssuer.issue(sellerId, monthKey, net) : null;

        return sellerSettlementRepository.save(SellerSettlement.close(
                sellerId, monthKey, gross, fee, net, records.size(), invoiceNo));
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
