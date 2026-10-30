package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.RefundAmountView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Schema(description = "주문·결제 당시 PG 금액·확인된 취소 근거를 구분합니다. null은 미확인이며 환불 완료 상태를 바꾸지 않습니다.")
public record RefundAmountDetailsResponse(long orderAmount,
        @Schema(description = "결제 당시 PG 실결제액이며 반환 완료 금액이 아닙니다.", nullable = true) Long pgPaidAmount,
        @Schema(description = "결제 당시 PG 할인액이며 반환 포인트가 아닙니다.", nullable = true) Long pgDiscountAmount,
        @Schema(description = "제공사가 확인한 취소 총액이며 현금 입금액이 아닙니다.", nullable = true) Long confirmedCancellationAmount,
        @Schema(description = "성공 취소 전체에서 직접 보고한 포인트 합계. 하나라도 미기록이면 null입니다.", nullable = true) Long confirmedPointRefundAmount,
        @Schema(description = "직접 확인된 결제수단별 반환액. 현재 근거가 없으면 null이며 승인 실결제액으로 대신하지 않습니다.", nullable = true) Long confirmedPaymentMethodRefundAmount,
        @Schema(description = "반환 금액 상세의 확인·보완 시각입니다.", nullable = true) Instant reportedAt,
        @Schema(description = "반환 근거 출처입니다.", nullable = true) String source,
        @Schema(description = "미확인인 금액 필드명입니다. 0원과 구분하며 금융 완료를 보류하는 사유가 아닙니다.") List<String> unconfirmedFields) {
    public static RefundAmountDetailsResponse from(RefundAmountView view) {
        List<String> unknown = new ArrayList<>();
        if (view.pgPaidAmount() == null) unknown.add("pgPaidAmount");
        if (view.pgDiscountAmount() == null) unknown.add("pgDiscountAmount");
        if (view.confirmedCancellationAmount() == null) unknown.add("confirmedCancellationAmount");
        if (view.confirmedPointRefundAmount() == null) unknown.add("confirmedPointRefundAmount");
        unknown.add("confirmedPaymentMethodRefundAmount");
        return new RefundAmountDetailsResponse(view.orderAmount(), view.pgPaidAmount(), view.pgDiscountAmount(),
                view.confirmedCancellationAmount(), view.confirmedPointRefundAmount(), null, view.reportedAt(), view.source(), List.copyOf(unknown));
    }
}
