package com.stove.settlement.api.application;

import com.stove.settlement.core.domain.SellerSettlement;
import com.stove.settlement.core.port.TaxInvoiceIssuer;
import com.stove.settlement.core.service.SellerSettlementService;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 월 마감 오케스트레이션. 순서는 "확정본 커밋 → 계산서 발행(트랜잭션 밖) → 번호 커밋" 이고
 * <b>판매자마다 독립 트랜잭션</b>이다. 이 클래스는 트랜잭션을 열지 않는다. [D-022]
 * docs/code-notes.md
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementCloseFacade {

    private final SellerSettlementService sellerSettlementService;
    private final TaxInvoiceIssuer taxInvoiceIssuer;

    /**
     * 월 마감. 이미 마감된 원장은 대상에서 빠지므로 재실행이 안전하다.
     *
     * @return 이번 실행에서 확정된 판매자별 확정본
     */
    public List<SellerSettlement> closeMonth(YearMonth month) {
        List<Long> sellerIds = sellerSettlementService.sellersToClose(month);
        if (sellerIds.isEmpty()) {
            log.info("마감 대상 없음 month={}", month);
            return List.of();
        }

        List<SellerSettlement> closed = new ArrayList<>();
        List<Long> failed = new ArrayList<>();

        for (Long sellerId : sellerIds) {
            try {
                SellerSettlement settlement = closeOne(sellerId, month);
                if (settlement != null) {
                    closed.add(settlement);
                }
            } catch (RuntimeException e) {
                // 한 명의 실패가 나머지를 막지 않는다 — 유실이 아니라 관측 가능한 보류다.
                failed.add(sellerId);
                log.error("판매자 마감 실패 — 다음 실행에서 재시도된다 sellerId={} month={}",
                        sellerId, month, e);
            }
        }

        log.info("정산 마감 month={} 확정={} 실패={}", month, closed.size(), failed.size());
        if (!failed.isEmpty()) {
            log.warn("마감되지 않은 판매자 month={} sellerIds={}", month, failed);
        }
        return closed;
    }

    private SellerSettlement closeOne(Long sellerId, YearMonth month) {
        SellerSettlement settlement = sellerSettlementService.closeSeller(sellerId, month);   // 커밋 1

        if (settlement == null) {
            // 마감할 원장이 없는데 대상에 들어왔다 = 발행만 남은 건이다.
            settlement = sellerSettlementService.find(sellerId, month);
        }

        if (settlement != null && settlement.needsTaxInvoice()) {
            String invoiceNo = taxInvoiceIssuer.issue(                                  // 트랜잭션 밖
                    sellerId, month.toString(), settlement.getNetAmount());
            sellerSettlementService.assignTaxInvoice(sellerId, month, invoiceNo);             // 커밋 2
            settlement.assignTaxInvoice(invoiceNo);
        }
        return settlement;
    }
}
