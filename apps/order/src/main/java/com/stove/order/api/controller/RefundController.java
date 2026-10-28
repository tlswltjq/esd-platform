package com.stove.order.api.controller;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import com.stove.order.api.controller.dto.RefundResponse;
import com.stove.order.api.controller.dto.RefundRetryRequestBody;
import com.stove.order.api.controller.dto.RefundRetryResponse;
import com.stove.order.api.controller.dto.RefundReconsiderationBody;
import com.stove.order.api.controller.dto.RefundReconsiderationResponse;
import com.stove.order.api.controller.dto.CustomerRefundReviewPage;
import com.stove.order.core.service.ReadRefund;
import com.stove.order.core.service.RequestRefund;
import com.stove.order.core.service.RequestRefundRetry;
import com.stove.order.core.service.RequestRefundReconsideration;
import com.stove.order.core.service.ReadRefundReviewRounds;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequiredArgsConstructor
@SecurityRequirement(name = "oauth2", scopes = "commerce")
@RequestMapping("/api/v1/orders/{orderNo}/refund")
public class RefundController {
    private final RequestRefund request;
    private final ReadRefund read;
    private final RequestRefundRetry retry;
    private final RequestRefundReconsideration reconsideration;
    private final ReadRefundReviewRounds reviews;

    @PostMapping("/reconsiderations")
    @Operation(operationId = "requestRefundReconsideration", summary = "내 반려 환불 재심사 접수",
            description = "환불 조회의 currentReview.reviewId를 previousReviewId로 지정하고 새 requestId와 필수 사유를 보냅니다. 마지막 반려 저장 후 정확히 7일까지 접수하며 횟수 제한은 없습니다. 같은 명령·회차·사유의 재전송은 최초 영수증을 반환합니다. 기한 내 접수 뒤 처리 지연은 접수 효력에 영향을 주지 않습니다. 202는 심사 접수이며 자동 환불이나 이용권 정지가 아닙니다. 원래 환불 자격과 판정 이력은 보존됩니다.")
    @ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "재심사 최초 접수 또는 동일 접수 영수증")})
    public ResponseEntity<ApiResponse<RefundReconsiderationResponse>> reconsider(@CurrentSubject String buyer, @PathVariable String orderNo,
            @RequestBody RefundReconsiderationBody body) {
        RefundReconsiderationResponse receipt = RefundReconsiderationResponse.from(reconsideration.execute(body.toInput(orderNo, buyer)));
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .location(URI.create("/api/v1/orders/" + orderNo + "/refund")).body(ApiResponse.ok(receipt));
    }

    @GetMapping("/reviews")
    @Operation(operationId = "readCustomerRefundReviews", summary = "내 환불 심사 이력", description = "최초 심사와 모든 재심사 회차를 회차 순으로 조회합니다. 관리자 명령 ID·판정자·승인 사유는 공개하지 않습니다. page/size 기본 0/20, 최대 100입니다.")
    public ResponseEntity<ApiResponse<CustomerRefundReviewPage>> reviews(@CurrentSubject String buyer, @PathVariable String orderNo,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(CustomerRefundReviewPage.from(reviews.customer(orderNo, buyer, page, size))));
    }

    @PostMapping("/retries")
    @Operation(operationId = "requestRefundRetry", summary = "내 주문 환불 명시적 재시도",
            description = "환불 조회의 retryableAttemptId를 previousAttemptId로 전달합니다. 확정 실패와 실제 이용권 복구가 끝난 현재 시도만 새 정지를 요청할 수 있습니다. 같은 이전 시도의 반복 요청은 최초 재시도 접수 영수증을 202로 반환합니다. receivedAt은 재시도 접수 시각이며 최초 환불 자격은 보존됩니다. 접수는 정지·PG 환불 완료를 뜻하지 않으며 현재 결과는 Location의 환불 조회로 확인합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "재시도 최초 접수 또는 동일 접수 영수증"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "이전 시도 ID 또는 요청 형식 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "본인 주문 또는 환불 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "지정한 시도가 없거나 현재 실패·복구 완료 조건 불충족")
    })
    public ResponseEntity<ApiResponse<RefundRetryResponse>> retry(@CurrentSubject String buyer, @PathVariable String orderNo,
            @RequestBody RefundRetryRequestBody body) {
        RefundRetryResponse response = RefundRetryResponse.from(retry.execute(body.toInput(buyer, orderNo)));
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .location(URI.create("/api/v1/orders/" + orderNo + "/refund")).body(ApiResponse.ok(response));
    }

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
