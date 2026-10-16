package com.stove.payment.api.controller;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import com.stove.payment.api.controller.dto.*;
import com.stove.payment.core.service.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/payments")
@SecurityRequirement(name="oauth2",scopes="commerce")
public class PaymentController {
    private final SearchPayments searchPayments;
    private final ReadPayment readPayment;
    @GetMapping public ApiResponse<PaymentPage> list(@CurrentSubject String buyer,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(PaymentPage.from(searchPayments.execute(buyer,page,size)));
    }
    @GetMapping("/{orderNo}") public ApiResponse<PaymentResponse> detail(@CurrentSubject String buyer,@PathVariable String orderNo) {
        return ApiResponse.ok(PaymentResponse.from(readPayment.execute(orderNo,buyer)));
    }
}
