package com.stove.settlement.core.domain;

import java.util.List;

public record ReconciliationResult(Long sellerId, String month, long customerPaid, long sellerDiscount,
                                   long platformExpense, long settlementBasis, long fee, long sellerPayout,
                                   int recordCount, int unclosedCount, int adjustmentCount,
                                   long closingBasisVariance, long closingFeeVariance,
                                   long closingPayoutVariance, boolean balanced, List<String> warnings) {}
