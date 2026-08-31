package com.stove.settlement.core.service;

import com.stove.common.event.payload.OrderLine;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.settlement.core.domain.FeePolicy;
import com.stove.settlement.core.domain.RecordType;
import com.stove.settlement.core.domain.SaleType;
import com.stove.settlement.core.domain.SettlementRecord;
import com.stove.settlement.core.domain.SettlementRecordRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매출 원장 — 무엇을 팔았고 무엇을 물러줬는가.
 *
 * <p>결제 이벤트로만 늘어나고, 마감이 그 줄을 닫는다. 수수료 계산은 {@link FeePolicy} 가,
 * 판매자별 확정본은 {@link SellerSettlementService} 가 맡는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SettlementRecordService {

    /** Kafka 컨슈머 그룹이자 Inbox 멱등 키. 리스너도 이 상수를 참조한다 — {@code ConsumerGroupRules} 참고. */
    public static final String CONSUMER_GROUP = "settlement";

    private final SettlementRecordRepository recordRepository;
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

    /** 이번 달 미마감 원장을 가진 판매자. 마감이 판매자 단위로 쪼개지므로 대상만 먼저 읽는다. */
    @Transactional(readOnly = true)
    public List<Long> sellerIdsWithUnclosed(YearMonth month) {
        return recordRepository.findSellerIdsToClose(month.toString());
    }

    /**
     * 판매자 한 명의 미마감 원장을 마감 처리하고 그 목록을 돌려준다.
     *
     * <p><b>읽기와 close 를 한 호출로 묶는다.</b> 합산은 이 목록으로
     * {@link SellerSettlementService} 가 하는데, 부르는 쪽 트랜잭션에 그대로 참여하므로
     * "합산에 들어간 원장" 과 "close 된 원장" 이 어긋날 수 없다. 둘로 나눠 열어 두면
     * 그 사이에 새 원장이 끼어들어 <b>마감됐는데 어디에도 없는 금액</b>이 생긴다.
     */
    public List<SettlementRecord> closeUnclosed(Long sellerId, YearMonth month) {
        List<SettlementRecord> records = recordRepository
                .findBySettlementMonthAndSellerIdAndClosedIsFalse(month.toString(), sellerId);
        records.forEach(SettlementRecord::close);
        return records;
    }

    @Transactional(readOnly = true)
    public List<SettlementRecord> findByOrder(String orderNo) {
        return recordRepository.findByOrderNo(orderNo);
    }

    @Transactional(readOnly = true)
    public List<SettlementRecord> findBySeller(Long sellerId, YearMonth month) {
        return recordRepository.findBySellerIdAndSettlementMonth(sellerId, month.toString());
    }
}
