package com.stove.common.kafka.contract.payload;

import java.time.Instant;

/** The request owner's final fact, including an earlier decision that prevented withdrawal. */
public record ProjectWithdrawalResult(
        Long withdrawalId, Long requestId, Long projectId, long contentVersion,
        String productCode, String status, String actor, String feedback,
        Instant finalizedAt, String requestEventId) {}
