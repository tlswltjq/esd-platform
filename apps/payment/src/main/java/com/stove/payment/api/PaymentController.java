package com.stove.payment.api;

import com.stove.common.core.response.ApiResponse;
import com.stove.payment.api.dto.PaymentResponse;
import com.stove.payment.api.dto.PgCallbackRequest;
import com.stove.payment.api.dto.PreparePaymentRequest;
import com.stove.payment.api.dto.PreparePaymentResponse;
import com.stove.payment.application.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    @GetMapping("/{orderNo}")
    public ApiResponse<PaymentResponse> get(@PathVariable String orderNo) {
        return ApiResponse.ok(PaymentResponse.from(paymentService.getPayment(orderNo)));
    }

    @PostMapping("/{orderNo}/prepare")
    public ApiResponse<PreparePaymentResponse> prepare(@PathVariable String orderNo,
                                                       @Valid @RequestBody PreparePaymentRequest request) {
        return ApiResponse.ok(paymentService.prepare(orderNo, request.method()));
    }

    /** PG 승인 콜백 수신 엔드포인트 (실제 운영에서는 서명 검증·IP 화이트리스트가 앞단에 붙는다) */
    @PostMapping("/callback")
    public ApiResponse<Void> callback(@Valid @RequestBody PgCallbackRequest request) {
        paymentService.handleCallback(request);
        return ApiResponse.ok();
    }

    @PostMapping("/{orderNo}/cancel")
    public ApiResponse<Void> cancel(@PathVariable String orderNo,
                                    @RequestParam(defaultValue = "USER_REFUND") String reason) {
        paymentService.cancel(orderNo, reason);
        return ApiResponse.ok();
    }
}
