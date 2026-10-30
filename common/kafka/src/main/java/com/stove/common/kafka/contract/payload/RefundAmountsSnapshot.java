package com.stove.common.kafka.contract.payload;

import java.time.Instant;
import java.util.List;

/** Original approval amounts and successful cancellation facts. Missing values stay unknown. */
public record RefundAmountsSnapshot(Long pgPaidAmount, Long pgDiscountAmount,
        List<RefundCancellationAmount> cancellations, Instant providerCancelledAt) { }
