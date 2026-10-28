package com.stove.order.api.controller;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import com.stove.order.api.controller.dto.RefundResponse;
import com.stove.order.core.service.ReadRefund;
import com.stove.order.core.service.RequestRefund;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequiredArgsConstructor
@SecurityRequirement(name = "oauth2", scopes = "commerce")
@RequestMapping("/api/v1/orders/{orderNo}/refund")
public class RefundController {
    private final RequestRefund request;
    private final ReadRefund read;

    @PostMapping
    @Operation(summary = "내 주문 전액 환불 접수", description = "요청 금액은 원주문의 전액으로 정하며 입력 본문은 사용하지 않습니다. 같은 주문의 반복 접수는 기존 환불을 반환합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "환불 접수 또는 기존 처리 대기"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "기존 환불의 최종 결과")
    })
    public ResponseEntity<ApiResponse<RefundResponse>> request(@CurrentSubject String buyer, @PathVariable String orderNo) {
        request.execute(buyer, orderNo);
        RefundResponse response = RefundResponse.from(read.execute(orderNo, buyer));
        HttpStatus status = response.status() == RefundResponse.Status.COMPLETED || response.status() == RefundResponse.Status.FAILED || response.status() == RefundResponse.Status.REJECTED
                ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .location(URI.create("/api/v1/orders/" + response.orderNo() + "/refund")).body(ApiResponse.ok(response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<RefundResponse>> read(@CurrentSubject String buyer, @PathVariable String orderNo) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(RefundResponse.from(read.execute(orderNo, buyer))));
    }
}
