package com.stove.settlement.api.application;

import com.stove.settlement.core.domain.SellerSettlement;
import com.stove.settlement.core.port.TaxInvoiceIssuer;
import com.stove.settlement.core.service.SettlementService;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 월 마감 오케스트레이션.
 *
 * <p>여기서 조율이 필요한 이유는 결제 환불({@code RefundFacade})과 같다 —
 * <b>세금계산서 발행은 되돌릴 수 없고, 트랜잭션은 되돌릴 수 있다.</b>
 * 둘을 한 트랜잭션에 넣으면 뒤쪽이 실패했을 때 국세청에는 계산서가 있는데
 * 우리 장부에는 마감 기록이 없는 상태가 생긴다(docs/defects.md 의 D-022).
 *
 * <p>그래서 순서를 이렇게 고정한다.
 *
 * <ol>
 *   <li>확정본 커밋 — 판매자 한 명의 원장을 합산해 {@code seller_settlement} 에 쓰고 원장을 close</li>
 *   <li>세금계산서 발행 (트랜잭션 밖)</li>
 *   <li>발행 번호 커밋</li>
 * </ol>
 *
 * <p>어느 단계에서 멈추든 결과가 관측 가능하다. 1번 전이면 그 판매자는 손대지 않은 상태고,
 * 2번에서 멈추면 <b>마감은 됐고 계산서는 아직</b>인 상태로 남아 재실행 대상이 된다.
 * 3번에서 멈춰도 마찬가지이며, 발행이 {@code (sellerId, month)} 기준 멱등이라
 * 재실행이 이중 발행이 되지 않는다({@link TaxInvoiceIssuer#issue} 계약).
 *
 * <p><b>판매자마다 독립 트랜잭션이다.</b> 예전에는 그 달 전체가 한 트랜잭션이라
 * 100명 중 87번째에서 예외가 나면 앞의 86장이 이미 발행된 채 전량 롤백됐다.
 * 이제 한 명이 실패해도 나머지는 확정되고, 실패한 판매자만 다음 실행에서 다시 잡힌다.
 *
 * <p>트랜잭션을 열지 않는다 — 동기 외부 호출이 쓰기 트랜잭션 안으로 들어오면 안 되기 때문이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementCloseFacade {

    private final SettlementService settlementService;
    private final TaxInvoiceIssuer taxInvoiceIssuer;

    /**
     * 월 마감. 이미 마감된 원장은 대상에서 빠지므로 재실행이 안전하다.
     *
     * @return 이번 실행에서 확정된 판매자별 확정본
     */
    public List<SellerSettlement> closeMonth(YearMonth month) {
        List<Long> sellerIds = settlementService.sellersToClose(month);
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
                // 한 판매자의 실패가 나머지의 마감을 막지 않는다. 이 건은 원장이 미마감으로
                // 남으므로 다음 실행에서 다시 잡힌다 — 유실이 아니라 관측 가능한 보류다.
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
        SellerSettlement settlement = settlementService.closeSeller(sellerId, month);   // 커밋 1

        if (settlement == null) {
            // 마감할 원장이 없는데 대상에 들어온 판매자 = 발행만 남은 건이다.
            settlement = settlementService.getSettlement(sellerId, month);
        }

        if (settlement != null && settlement.needsTaxInvoice()) {
            String invoiceNo = taxInvoiceIssuer.issue(                                  // 트랜잭션 밖
                    sellerId, month.toString(), settlement.getNetAmount());
            settlementService.assignTaxInvoice(sellerId, month, invoiceNo);             // 커밋 2
            settlement.assignTaxInvoice(invoiceNo);
        }
        return settlement;
    }
}
