package com.stove.settlement.core.port;

/** 세금계산서 발행 연동 포트(국세청/전자세금계산서 사업자). */
public interface TaxInvoiceIssuer {

    /**
     * 세금계산서를 발행한다.
     *
     * <p><b>{@code (sellerId, settlementMonth)} 기준 멱등이어야 한다.</b> 같은 판매자의 같은 달에
     * 다시 요청해도 이중 발행이 되지 않아야 한다는 뜻이다. 마감은 "확정본 커밋 → 발행 →
     * 번호 커밋" 순서로 진행되고 중간에 멈춘 건은 다음 실행에서 재시도되므로,
     * 이 성질이 없으면 재시도가 곧 이중 발행이 된다({@code PgClient#cancel} 과 같은 이유).
     *
     * @return 발행 번호
     */
    String issue(Long sellerId, String settlementMonth, long netAmount);
}
