package com.stove.payment.api.controller;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.common.web.response.ApiResponse;
import com.stove.payment.api.controller.dto.ApprovalReviewNoticeResponse;
import com.stove.payment.core.domain.ApprovalToken;
import com.stove.payment.core.domain.PaymentApproval;
import com.stove.payment.core.domain.PaymentApprovalInput;
import com.stove.payment.core.domain.PaymentApprovalStatus;
import com.stove.payment.core.service.ReadApprovalReviewNotice;
import com.stove.payment.core.service.ReadPaymentApproval;
import com.stove.payment.core.service.RequestPaymentApproval;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.net.URI;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments/{orderNo}/approvals")
@ConditionalOnProperty(name="stove.payment.pg.provider",havingValue="PORTONE_TEST")
@SecurityRequirement(name="oauth2",scopes="commerce")
public class PaymentApprovalController {
    private final RequestPaymentApproval request;
    private final ReadPaymentApproval read;
    private final ReadApprovalReviewNotice reviewNotices;
    public PaymentApprovalController(RequestPaymentApproval request,ReadPaymentApproval read,ReadApprovalReviewNotice reviewNotices) {
        this.request=request; this.read=read; this.reviewNotices=reviewNotices;
    }
    @PostMapping
    public ResponseEntity<ApiResponse<Reception>> receive(@CurrentSubject String buyer,@PathVariable String orderNo,@RequestBody ApprovalRequest body) {
        PaymentApproval approval=request.execute(buyer,body.toInput(orderNo));
        URI location=URI.create("/api/v1/payments/"+orderNo+"/approvals/"+approval.getApprovalId());
        return ResponseEntity.accepted().location(location).cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(new Reception(approval.getApprovalId(),orderNo,approval.getPaymentId(),approval.getReceivedAt())));
    }
    @GetMapping("/{requestId}")
    public ResponseEntity<ApiResponse<Progress>> read(@CurrentSubject String buyer,@PathVariable String orderNo,@PathVariable String requestId) {
        PaymentApproval approval=read.execute(buyer,orderNo,requestId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(new Progress(approval.getApprovalId(),
                approval.getOrderNo(),approval.getPaymentId(),approval.getStatus(),approval.getReceivedAt(),approval.getExpiresAt(),approval.getResolvedAt(),
                ApprovalReviewNoticeResponse.from(reviewNotices.forApproval(approval)))));
    }
    public record ApprovalRequest(String requestId,String txId,String paymentToken) {
        PaymentApprovalInput toInput(String orderNo) {
            try { return new PaymentApprovalInput(requestId,orderNo,txId,new ApprovalToken(paymentToken)); }
            catch (IllegalArgumentException invalid) { throw new BusinessException(ErrorCode.INVALID_REQUEST,"Invalid payment authentication result"); }
        }
        @Override public String toString() { return "ApprovalRequest[requestId="+requestId+",token=redacted]"; }
    }
    public record Reception(String requestId,String orderNo,String paymentId,Instant receivedAt) { }
    public record Progress(String requestId,String orderNo,String paymentId,PaymentApprovalStatus status,Instant receivedAt,Instant expiresAt,Instant resolvedAt,
            @Schema(description="금융 보류 중이거나 보류 종결로 끝난 승인의 고객 안내(청구 여부별 코드·문구·보류 시작 시각). 그 밖에는 null입니다.",nullable=true)
            ApprovalReviewNoticeResponse reviewNotice) { }
}
