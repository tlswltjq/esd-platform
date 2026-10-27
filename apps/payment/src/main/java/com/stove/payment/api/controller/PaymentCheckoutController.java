package com.stove.payment.api.controller;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import com.stove.payment.api.controller.dto.PaymentCheckoutResponse;
import com.stove.payment.core.service.ReadPaymentCheckout;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "stove.payment.pg.provider", havingValue = "PORTONE_TEST")
@SecurityRequirement(name = "oauth2", scopes = "commerce")
public class PaymentCheckoutController {
    private final ReadPaymentCheckout readCheckout;

    @GetMapping("/api/v1/payments/{orderNo}/checkout")
    public ResponseEntity<ApiResponse<PaymentCheckoutResponse>> read(@CurrentSubject String buyer, @PathVariable String orderNo) {
        PaymentCheckoutResponse checkout = PaymentCheckoutResponse.from(readCheckout.execute(orderNo, buyer));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(checkout));
    }
}
