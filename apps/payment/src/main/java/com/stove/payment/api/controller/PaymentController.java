package com.stove.payment.api.controller;

import com.stove.common.core.response.ApiResponse;
import com.stove.common.security.CommerceIdentity;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import com.stove.payment.api.controller.dto.PaymentResponse;
import com.stove.payment.api.controller.dto.PgCallbackRequest;
import com.stove.payment.api.controller.dto.PreparePaymentRequest;
import com.stove.payment.api.controller.dto.PreparePaymentResponse;
import com.stove.payment.api.application.PaymentCallbackFacade;
import com.stove.payment.api.application.RefundFacade;
import com.stove.payment.core.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final RefundFacade refundFacade;
    private final PaymentCallbackFacade paymentCallbackFacade;

    @GetMapping("/{orderNo}")
    @SecurityRequirement(name = "oauth2", scopes = "commerce")
    public ApiResponse<PaymentResponse> get(@PathVariable String orderNo,
                                            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(PaymentResponse.from(paymentService.getPaymentForMember(
                orderNo, CommerceIdentity.memberId(jwt))));
    }

    @PostMapping("/{orderNo}/prepare")
    @SecurityRequirement(name = "oauth2", scopes = "commerce")
    public ApiResponse<PreparePaymentResponse> prepare(@PathVariable String orderNo,
                                                       @AuthenticationPrincipal Jwt jwt,
                                                       @Valid @RequestBody PreparePaymentRequest request) {
        return ApiResponse.ok(PreparePaymentResponse.from(paymentService.prepareForMember(
                orderNo, CommerceIdentity.memberId(jwt), request.method())));
    }

    /**
     * PG 결제 결과 콜백. PgCallbackAuthenticationFilter가 원본 본문 서명을 먼저 확인한다.
     *
     * <p>승인과 거절이 한 URL 로 들어와 {@code result} 로 갈린다. 판별할 수 없는 본문은
     * 여기 오기 전에 역직렬화에서 400 으로 끊긴다 — {@link PgCallbackRequest} 참고.
     */
    @PostMapping("/callback")
    @Parameters({
            @Parameter(name = "X-Pg-Timestamp", in = ParameterIn.HEADER, required = true,
                    description = "Unix epoch seconds; at most five minutes old"),
            @Parameter(name = "X-Pg-Signature", in = ParameterIn.HEADER, required = true,
                    description = "Hex HMAC-SHA256 of timestamp + '.' + raw request body")
    })
    public ApiResponse<Void> callback(@Valid @RequestBody PgCallbackRequest request) {
        switch (request) {
            case PgCallbackRequest.Approved approved -> paymentCallbackFacade.approve(approved.toApproval());
            case PgCallbackRequest.Declined declined -> paymentService.handleDecline(declined.toDecline());
        }
        return ApiResponse.ok();
    }

    @PostMapping("/{orderNo}/cancel")
    @SecurityRequirement(name = "oauth2", scopes = "commerce")
    public ApiResponse<Void> cancel(@PathVariable String orderNo,
                                    @AuthenticationPrincipal Jwt jwt,
                                    @RequestParam(defaultValue = "USER_REFUND") String reason) {
        refundFacade.refundForMember(orderNo, CommerceIdentity.memberId(jwt), reason);
        return ApiResponse.ok();
    }
}
