package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.PgApproval;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * PG 승인 콜백.
 * {@code idempotencyKey} 는 PG 승인 거래의 고유값(없으면 pgTxId)을 사용하며,
 * DB 유니크 제약과 짝을 이뤄 중복 승인을 차단한다.
 */
public record PgCallbackRequest(
        @NotBlank String orderNo,
        @NotBlank String pgTxId,
        @NotNull @Positive Long paidAmount,
        @NotBlank String idempotencyKey,
        String method
) {
    public PgApproval toApproval() {
        return new PgApproval(orderNo, pgTxId, paidAmount, idempotencyKey);
    }
}
