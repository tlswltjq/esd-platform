package com.stove.payment.api.controller;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import com.stove.payment.api.controller.dto.RefundCancellationHoldPage;
import com.stove.payment.api.controller.dto.RefundCancellationHoldResponse;
import com.stove.payment.api.controller.dto.RefundCancellationResumeBody;
import com.stove.payment.api.controller.dto.RefundCancellationResumeResponse;
import com.stove.payment.core.domain.RefundCancellationHoldLookup;
import com.stove.payment.core.domain.RefundCancellationHoldQuery;
import com.stove.payment.core.service.ReadRefundCancellationHolds;
import com.stove.payment.core.service.RequestRefundCancellationResume;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequiredArgsConstructor
@SecurityRequirement(name = "oauth2", scopes = "commerce")
@RequestMapping("/api/v1/admin/refund-cancellation-holds")
public class AdminRefundCancellationController {
    private final ReadRefundCancellationHolds read;
    private final RequestRefundCancellationResume resume;

    @GetMapping
    @Operation(operationId = "searchRefundCancellationHolds", summary = "관리자 환불 송신 중단 목록",
            description = "기본 activeOnly=true는 현재 중단만 조회합니다. false는 과거 재개·미적용 관측도 포함합니다. 기한이 지난 중단도 표시하며 observedAt·ID 순으로 조회합니다. 재개 가능 여부는 조회 시점의 안내이며 실행 시 다시 검증합니다.")
    public ResponseEntity<ApiResponse<RefundCancellationHoldPage>> search(@RequestParam(defaultValue = "true") boolean activeOnly,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(RefundCancellationHoldPage.from(read.search(new RefundCancellationHoldQuery(activeOnly, page, size)))));
    }

    @GetMapping("/{holdId}")
    @Operation(operationId = "readRefundCancellationHold", summary = "관리자 환불 송신 중단 상세",
            description = "중단 사유·현재 여부·원래 3시간 기한·재개 가능 여부와 이전 재개 지시를 확인합니다. PG 키·본문·비밀 설정은 응답에 포함하지 않습니다.")
    public ResponseEntity<ApiResponse<RefundCancellationHoldResponse>> detail(@PathVariable String holdId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(RefundCancellationHoldResponse.from(read.read(new RefundCancellationHoldLookup(holdId)))));
    }

    @PostMapping("/{holdId}/resume")
    @Operation(operationId = "resumeRefundCancellation", summary = "관리자 환불 송신 중단 재개",
            description = "ADMIN sub·확인 사유를 기록하고 기존 시도의 송신을 다시 예약합니다. 200은 지시 저장이며 PG 환불 완료가 아닙니다. 같은 requestId·시도·중단·판정자·사유의 재전송은 최초 영수증을 반환합니다. 다른 내용은 409입니다. 원래 PG 키·본문·최초 준비 시각·3시간 기한을 유지하며, 새 중단은 별도 지시가 필요합니다.")
    public ResponseEntity<ApiResponse<RefundCancellationResumeResponse>> resume(@CurrentSubject String operator,
            @PathVariable String holdId, @RequestBody RefundCancellationResumeBody body) {
        RefundCancellationResumeResponse receipt = RefundCancellationResumeResponse.from(resume.execute(body.toInput(holdId, operator)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .location(URI.create("/api/v1/admin/refund-cancellation-holds/" + receipt.holdId())).body(ApiResponse.ok(receipt));
    }
}
