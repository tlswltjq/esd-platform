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
 *
 * <p>원장 자체는 {@link SettlementRecordService} 가 소유한다. 마감 한 건이 두 애그리거트를
 * 가로지르지만 <b>한 트랜잭션이어야 하므로</b> 쪼개지 않는다 — 합산에 쓴 원장과 close 된 원장이
 * 갈리면 "마감됐는데 어디에도 없는 금액" 이 생긴다. 원장 쪽 접근은 그 서비스에 위임하고
 * (같은 트랜잭션에 참여한다) 여기서는 확정본만 만진다.
 *
 * <p>세금계산서 발행은 여기 없다. 되돌릴 수 없는 외부 호출이라 트랜잭션 밖이어야 하고,
 * 순서는 {@link com.stove.settlement.api.application.SettlementCloseFacade} 가 잡는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SellerSettlementService {

    private final SellerSettlementRepository sellerSettlementRepository;
    private final SettlementRecordService settlementRecordService;

    /**
     * 이번 달 마감 대상 판매자.
     *
     * <p>마감은 판매자 단위 트랜잭션으로 쪼개져 있으므로
     * ({@link com.stove.settlement.api.application.SettlementCloseFacade} 참고)
     * 오케스트레이터가 먼저 대상만 읽는다.
     */
    @Transactional(readOnly = true)
    public List<Long> sellersToClose(YearMonth month) {
        // 두 부류를 합친다.
        //  1. 미마감 원장이 있는 판매자 — 보통의 마감 대상
        //  2. 마감은 끝났는데 계산서가 없는 판매자 — 발행이 실패했던 건
        //
        // 2번을 빼면 발행 실패가 영구 방치된다. 원장이 이미 close 되어 1번 기준으로는
        // 잡히지 않기 때문이다. 발행이 트랜잭션 밖으로 나오면서 생긴 새 경로다.
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
     *
     * <p>세금계산서는 여기서 발행하지 않는다. 발행은 되돌릴 수 없는 외부 호출이라
     * 트랜잭션 안에 들어오면 뒤가 깨졌을 때 <b>장부에는 없는 계산서</b>가 남는다 —
     * 결제 쪽 [D-006] 과 같은 모양이고, 여기서는 [D-022] 였다.
     *
     * <p>확정본을 먼저 커밋하고, 발행은 조율 계층이 커밋 뒤에 한다. 중간에 멈추면
     * "마감은 됐고 계산서는 아직"이라는 관측 가능한 상태가 남아 재시도 대상이 된다.
     *
     * <p>미마감 원장은 예외 없이 전부 확정본에 반영한다. 반영 대상과 close 대상이 어긋나면
     * "마감됐는데 어디에도 없는 금액"이 생긴다 — 그래서 원장을 집는 것과 닫는 것이 한 호출이다.
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

    @Transactional(readOnly = true)
    public List<SellerSettlement> findClosed(YearMonth month) {
        return sellerSettlementRepository.findBySettlementMonth(month.toString());
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
}
