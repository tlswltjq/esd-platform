package com.stove.payment.api.controller;

import com.stove.common.core.response.ApiResponse;
import com.stove.common.security.CommerceIdentity;
import com.stove.payment.api.application.PaymentSimulatorFacade;
import com.stove.payment.api.controller.dto.PaymentResponse;
import com.stove.payment.api.controller.dto.SimulatorCheckoutResponse;
import com.stove.payment.api.controller.dto.SimulatorDeclineRequest;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 데모·테스트 프로필에서만 존재하는 PG 제어면. 결과 전이는 실제 결제 경로를 사용한다. */
@RestController
@Profile("!prod & (demo | test)")
@RequiredArgsConstructor
@RequestMapping("/api/v1/payments/simulator")
public class PaymentSimulatorController {

    private final PaymentSimulatorFacade simulator;

    @GetMapping("/checkout/{pgTxId}")
    @SecurityRequirement(name = "oauth2", scopes = "commerce")
    public ApiResponse<SimulatorCheckoutResponse> checkout(@PathVariable String pgTxId,
                                                            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(SimulatorCheckoutResponse.from(
                simulator.checkout(pgTxId, CommerceIdentity.memberId(jwt))));
    }

    @PostMapping("/{orderNo}/approve")
    @SecurityRequirement(name = "oauth2", scopes = "commerce")
    public ApiResponse<PaymentResponse> approve(@PathVariable String orderNo) {
        return ApiResponse.ok(PaymentResponse.from(simulator.approve(orderNo)));
    }

    @PostMapping("/{orderNo}/decline")
    @SecurityRequirement(name = "oauth2", scopes = "commerce")
    public ApiResponse<PaymentResponse> decline(@PathVariable String orderNo,
                                                @Valid @RequestBody SimulatorDeclineRequest request) {
        return ApiResponse.ok(PaymentResponse.from(simulator.decline(
                orderNo, request.reasonCode(), request.reason())));
    }

    @PostMapping("/{orderNo}/timeout")
    @SecurityRequirement(name = "oauth2", scopes = "commerce")
    public ApiResponse<PaymentResponse> timeout(@PathVariable String orderNo) {
        return ApiResponse.ok(PaymentResponse.from(simulator.timeout(orderNo)));
    }

    @PostMapping("/{orderNo}/refund")
    @SecurityRequirement(name = "oauth2", scopes = "commerce")
    public ApiResponse<PaymentResponse> refund(@PathVariable String orderNo) {
        return ApiResponse.ok(PaymentResponse.from(simulator.refund(orderNo)));
    }
}
