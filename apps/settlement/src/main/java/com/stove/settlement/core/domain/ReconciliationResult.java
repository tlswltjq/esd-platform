package com.stove.settlement.core.domain;

import java.util.List;

/**
 * 판매자 월별 원장과 마감 집계의 일치 여부를 확인한 대사 결과.
 *
 * @param sellerId 판매자를 나타내는 워크스페이스 식별자.
 * @param month 대사할 정산 귀속 월(yyyy-MM).
 * @param customerPaid 원장에 기록된 고객 실결제액 합계.
 * @param sellerDiscount 판매자가 부담한 할인액 합계.
 * @param platformExpense 플랫폼이 부담한 할인 비용.
 * @param settlementBasis 수수료와 판매자 몫을 계산하는 정산 기준액.
 * @param fee 원장에 기록된 수수료 합계.
 * @param sellerPayout 수수료 차감 후 판매자에게 배분할 금액.
 * @param recordCount 집계에 포함한 정산 원장 행 수.
 * @param unclosedCount 아직 월 마감에 포함하지 않은 원장 행 수.
 * @param adjustmentCount 기존 마감 월을 참조하는 조정 원장 행 수.
 * @param closingBasisVariance 월 마감과 마감된 원장의 정산 기준액 차이.
 * @param closingFeeVariance 월 마감과 마감된 원장의 수수료 차이.
 * @param closingPayoutVariance 월 마감과 마감된 원장의 판매자 배분액 차이.
 * @param balanced 내부 원장과 마감 결과의 대사 검사를 모두 통과했는지 여부.
 * @param warnings 대사 과정에서 발견한 불일치 또는 확인 사항.
 */
public record ReconciliationResult(Long sellerId, String month, long customerPaid, long sellerDiscount,
                                   long platformExpense, long settlementBasis, long fee, long sellerPayout,
                                   int recordCount, int unclosedCount, int adjustmentCount,
                                   long closingBasisVariance, long closingFeeVariance,
                                   long closingPayoutVariance, boolean balanced, List<String> warnings) {}
