package com.stove.common.kafka.contract.payload;

/** Provider-reported cancellation total and optional points, not a cash refund amount. */
public record RefundCancellationAmount(String cancellationId, long totalAmount, Long pointAmount) { }
