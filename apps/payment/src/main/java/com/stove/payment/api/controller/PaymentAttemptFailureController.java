package com.stove.payment.api.controller;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import com.stove.payment.api.controller.dto.PaymentAttemptFailurePage;
import com.stove.payment.core.domain.PaymentFailureHistoryQuery;
import com.stove.payment.core.service.ReadPaymentFailureHistory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequiredArgsConstructor
@SecurityRequirement(name = "oauth2", scopes = "commerce")
public class PaymentAttemptFailureController {
    private final ReadPaymentFailureHistory read;

    @GetMapping("/api/v1/payments/{orderNo}/attempt-failures")
    @Operation(operationId = "readPaymentAttemptFailures", summary = "내 결제의 실패한 시도 이력",
            description = "서버가 검증한 PortOne 결제 시도 실패를 요청 시각·실패 시각·ID 역순으로 조회합니다. 결제/주문 전체의 최종 실패가 아닙니다. 기존 PENDING의 동일 주문·결제창 정보를 사용하며 새 주문이나 결제 ID를 만들지 않습니다. 조회는 DB만 읽습니다. checkedAt은 마지막 정상 수집 시각이고 collectionPending은 재수집 대기 여부입니다. PG 거래 ID·원문 사유는 노출하지 않습니다.")
    public ResponseEntity<ApiResponse<PaymentAttemptFailurePage>> history(@CurrentSubject String buyer, @PathVariable String orderNo,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(PaymentAttemptFailurePage.from(
                read.execute(new PaymentFailureHistoryQuery(orderNo, buyer, page, size)))));
    }
}
