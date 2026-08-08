package com.stove.payment.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.stove.payment.core.domain.PgApproval;
import com.stove.payment.core.domain.PgDecline;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * PG 결제 결과 콜백. 승인과 거절이 <b>같은 URL</b> 로 들어온다 — 실 PG 도 콜백 주소는 하나다.
 *
 * <p>변형을 {@code result} 로 갈라 <b>검증 규칙을 따로</b> 건다. 한 record 로 합치면 거절에는
 * 없는 {@code paidAmount} 때문에 승인 쪽 {@code @NotNull @Positive} 를 풀어야 하고,
 * 그러면 돈이 들어오는 문의 검증이 선언에서 사라진다.
 *
 * <p><b>{@code result} 에 기본값을 두지 않는다.</b> 없거나 모르는 값이면 역직렬화가 실패하고
 * {@code HttpMessageNotReadableException} 이 400 으로 나간다(GlobalExceptionHandler, D-015).
 * 기본값을 승인으로 두면 "표현되지 않은 결과가 조용히 승인이 되는" 성질이 생기는데,
 * 그게 정확히 결제 실패 경로가 통째로 비어 있던 원인이다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "result")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PgCallbackRequest.Approved.class, name = "APPROVED"),
        @JsonSubTypes.Type(value = PgCallbackRequest.Declined.class, name = "DECLINED")
})
public sealed interface PgCallbackRequest {

    /**
     * 승인 콜백.
     * {@code idempotencyKey} 는 PG 승인 거래의 고유값(없으면 pgTxId)을 사용하며,
     * 결제 행 잠금과 짝을 이뤄 중복 승인을 차단한다.
     */
    record Approved(
            @NotBlank String orderNo,
            @NotBlank String pgTxId,
            @NotNull @Positive Long paidAmount,
            @NotBlank String idempotencyKey,
            String method
    ) implements PgCallbackRequest {

        public PgApproval toApproval() {
            return new PgApproval(orderNo, pgTxId, paidAmount, idempotencyKey);
        }
    }

    /**
     * 거절 콜백. {@code reasonCode} 는 필수다 — 사유 없는 실패는 운영에서 쓸 수 없고,
     * 거절 사유별 집계가 결제 연동 품질을 보는 유일한 창이다.
     */
    record Declined(
            @NotBlank String orderNo,
            @NotBlank String pgTxId,
            @NotBlank String reasonCode,
            String reason
    ) implements PgCallbackRequest {

        public PgDecline toDecline() {
            return new PgDecline(orderNo, pgTxId, reasonCode, reason);
        }
    }
}
