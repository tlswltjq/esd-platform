package com.stove.settlement.core.service;

import com.stove.settlement.core.domain.SellerSettlement;
import com.stove.settlement.core.domain.SellerSettlementRepository;
import com.stove.settlement.core.domain.SettlementRecord;
import com.stove.settlement.core.port.TaxInvoiceIssuer;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자별 월 확정본 — 원장을 합산해 닫고, 계산서 번호를 받아 적는다.
 * 마감 한 건이 두 애그리거트를 가로지르지만 <b>한 트랜잭션이어야 하므로</b> 쪼개지 않는다.
 * 세금계산서 발행은 여기 없다. docs/code-notes.md
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SellerSettlementService {

    private final SellerSettlementRepository sellerSettlementRepository;
    private final SettlementRecordService settlementRecordService;

    /** 이번 달 마감 대상 판매자. 마감이 판매자 단위라 오케스트레이터가 먼저 대상만 읽는다. */
    @Transactional(readOnly = true)
    public List<Long> sellersToClose(YearMonth month) {
        // 미마감 원장 + 마감됐으나 계산서가 없는 판매자.
        // 후자를 빼면 발행 실패가 영구 방치된다. docs/code-notes.md
        Stream<Long> withOpenRecords = settlementRecordService.sellerIdsWithUnclosed(month).stream();
        Stream<Long> awaitingInvoice = sellerSettlementRepository.findAwaitingTaxInvoice(month.toString())
                .stream()
                .map(SellerSettlement::getSellerId);

        return Stream.concat(withOpenRecords, awaitingInvoice).distinct().sorted().toList();
    }

    /** 이미 마감된 확정본. 발행만 남은 판매자를 조율 계층이 집어갈 때 쓴다. */
    @Transactional(readOnly = true)
    public SellerSettlement find(Long sellerId, YearMonth month) {
        return sellerSettlementRepository
                .findBySellerIdAndSettlementMonth(sellerId, month.toString())
                .orElse(null);
    }

    /**
     * 판매자 한 명의 마감을 <b>독립 트랜잭션</b>으로 확정한다.
     * <b>세금계산서를 여기서 발행하면 안 된다</b> [D-022] — 장부에 없는 계산서가 남는다.
     * docs/code-notes.md
     *
     * @return 확정본. 마감할 원장이 없으면 {@code null}
     */
    public SellerSettlement closeSeller(Long sellerId, YearMonth month) {
        String monthKey = month.toString();
        List<SettlementRecord> records = settlementRecordService.closeUnclosed(sellerId, month);
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

        return sellerSettlementRepository.save(settlement);
    }

    /**
     * 발행이 끝난 계산서 번호를 기록한다(마감 2단계). 이 커밋이 깨질 수 있으므로
     * {@link TaxInvoiceIssuer#issue} 는 {@code (sellerId, month)} 기준 멱등이어야 한다.
     */
    public void assignTaxInvoice(Long sellerId, YearMonth month, String taxInvoiceNo) {
        sellerSettlementRepository.findBySellerIdAndSettlementMonth(sellerId, month.toString())
                .ifPresent(settlement -> settlement.assignTaxInvoice(taxInvoiceNo));
    }

    @Transactional(readOnly = true)
    public List<SellerSettlement> findClosed(YearMonth month) {
        return sellerSettlementRepository.findBySettlementMonth(month.toString());
    }

    private SellerSettlement revise(SellerSettlement existing, long gross, long fee, long net,
                                    int recordCount, Long sellerId, String monthKey) {
        existing.accumulate(gross, fee, net, recordCount);

        if (existing.hasTaxInvoice()) {
            // 이미 발행된 계산서의 금액이 바뀌었다 — 수정세금계산서가 필요한 건이다.
            log.warn("마감 확정본 금액 변경 — 수정세금계산서 검토 필요 sellerId={} month={} 추가액={} 계산서={}",
                    sellerId, monthKey, net, existing.getTaxInvoiceNo());
        }
        // 순액이 지각 매출로 양수가 되면 needsTaxInvoice() 가 참이 되어 조율 계층이 발행한다.
        return existing;
    }
}
