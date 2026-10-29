package com.stove.order.api.controller;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.response.ApiResponse;
import com.stove.order.core.domain.OrderErrorCode;
import com.stove.order.core.domain.OrderPaymentAdmission;
import com.stove.order.core.domain.PaymentAdmissionInput;
import com.stove.order.core.domain.PaymentAdmissionFailureInput;
import com.stove.order.core.service.RecordOrderPaymentAttemptFailure;
import com.stove.order.core.service.RequestOrderPaymentProcessing;
import io.swagger.v3.oas.annotations.Hidden;
import java.time.Instant;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("/internal/v1/orders/{orderNo}/payment-admissions")
public class OrderPaymentAdmissionController {
    private final RequestOrderPaymentProcessing begin;
    private final RecordOrderPaymentAttemptFailure failures;
    public OrderPaymentAdmissionController(RequestOrderPaymentProcessing begin,RecordOrderPaymentAttemptFailure failures) {
        this.begin = begin; this.failures=failures;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AdmissionResponse>> admit(@PathVariable String orderNo,
            @RequestBody AdmissionRequest request) {
        // Expiry must commit before the HTTP rejection; do not throw inside the admission transaction.
        OrderPaymentAdmission receipt = begin.execute(request.toInput(orderNo))
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_EXPIRED));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(AdmissionResponse.from(receipt)));
    }

    @PostMapping("/{admissionId}/failure")
    public ResponseEntity<ApiResponse<FailureResponse>> fail(@PathVariable String orderNo,@PathVariable String admissionId,
            @RequestBody FailureRequest request) {
        OrderPaymentAdmission receipt=failures.execute(new PaymentAdmissionFailureInput(admissionId,orderNo,
                request.paymentId(),request.supplierAttemptId(),request.failedAt()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(new FailureResponse(receipt.getAdmissionId(),
                receipt.getOrderNo(),receipt.getPaymentId(),receipt.getClosedAt(),receipt.getClosedOrderStatus())));
    }
    public record FailureRequest(String paymentId,String supplierAttemptId,Instant failedAt) { }
    public record FailureResponse(String admissionId,String orderNo,String paymentId,Instant closedAt,String orderStatus) { }

    public record AdmissionRequest(String admissionId, String requestEventId, String paymentId,
            String buyerSubject, long amount, String currency) {
        PaymentAdmissionInput toInput(String orderNo) {
            return new PaymentAdmissionInput(admissionId, orderNo, requestEventId, paymentId, buyerSubject, amount, currency);
        }
    }
    public record AdmissionResponse(String admissionId, String orderNo, String paymentId, Instant admittedAt, Instant expiresAt) {
        static AdmissionResponse from(OrderPaymentAdmission receipt) {
            return new AdmissionResponse(receipt.getAdmissionId(), receipt.getOrderNo(), receipt.getPaymentId(),
                    receipt.getAdmittedAt(), receipt.getExpiresAt());
        }
    }
}
