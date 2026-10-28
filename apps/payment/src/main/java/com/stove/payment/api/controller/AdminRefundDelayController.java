package com.stove.payment.api.controller;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import com.stove.payment.api.controller.dto.RefundDelayPage;
import com.stove.payment.api.controller.dto.RefundDelayResponse;
import com.stove.payment.api.controller.dto.RefundInvestigationBody;
import com.stove.payment.api.controller.dto.RefundInvestigationPage;
import com.stove.payment.api.controller.dto.RefundInvestigationResponse;
import com.stove.payment.core.domain.RefundDelayLookup;
import com.stove.payment.core.domain.RefundDelayQuery;
import com.stove.payment.core.service.ReadRefundDelays;
import com.stove.payment.core.service.RecordRefundInvestigation;
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
@RequestMapping("/api/v1/admin/refund-delays")
public class AdminRefundDelayController {
    private final ReadRefundDelays read;
    private final RecordRefundInvestigation record;

    @GetMapping
    @Operation(operationId = "searchRefundDelays", summary = "관리자 장기 미확정 환불 목록",
            description = "PortOne 실행 중 최초 준비 후 3시간 이상이고 현재 PENDING·환불 미확정인 시도를 준비 시각·ID 순으로 조회합니다. 자동 중단 관측 여부와 무관하며 조회는 PG 호출이나 일정 변경을 하지 않습니다.")
    public ResponseEntity<ApiResponse<RefundDelayPage>> search(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(RefundDelayPage.from(read.search(new RefundDelayQuery(page, size)))));
    }

    @GetMapping("/{attemptId}")
    @Operation(operationId = "readRefundDelay", summary = "관리자 장기 미확정 환불 상세",
            description = "원래 3시간 경계가 지난 준비 건의 현재 상태와 저장된 조회 일정·접수 근거를 확인합니다. 완료 뒤에도 조회할 수 있습니다. 일정은 PG 조회 성공의 증거가 아니며 실제 License 상태는 이 응답으로 확정하지 않습니다. PG 키·본문·비밀 설정은 포함하지 않습니다.")
    public ResponseEntity<ApiResponse<RefundDelayResponse>> detail(@PathVariable String attemptId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(RefundDelayResponse.from(read.read(new RefundDelayLookup(attemptId)))));
    }

    @GetMapping("/{attemptId}/investigations")
    @Operation(operationId = "readRefundInvestigations", summary = "관리자 환불 조사 기록 이력",
            description = "저장 시각·ID 순으로 확인 내용과 ADMIN 신원을 조회합니다. 금융 결과가 확정돼도 기존 기록을 보존합니다.")
    public ResponseEntity<ApiResponse<RefundInvestigationPage>> history(@PathVariable String attemptId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(RefundInvestigationPage.from(read.history(new RefundDelayLookup(attemptId), new RefundDelayQuery(page, size)))));
    }

    @PostMapping("/{attemptId}/investigations")
    @Operation(operationId = "recordRefundInvestigation", summary = "관리자 장기 미확정 환불 조사 기록",
            description = "현재 장기 미확정인 시도에 확인 내용을 추가합니다. ADMIN sub는 JWT에서, 저장 시각은 서버에서 기록합니다. 같은 requestId·시도·운영자·내용은 확정 결과 뒤에도 최초 영수증을 반환하고 다른 내용은 409입니다. 200은 기록 저장이며 PG 호출·일정 재개·실패/복구/완료·새 실행을 수행하지 않습니다. 기존 기록은 수정·삭제하지 않습니다.")
    public ResponseEntity<ApiResponse<RefundInvestigationResponse>> record(@CurrentSubject String operator,
            @PathVariable String attemptId, @RequestBody RefundInvestigationBody body) {
        RefundInvestigationResponse receipt = RefundInvestigationResponse.from(record.execute(body.toInput(attemptId, operator)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).location(URI.create("/api/v1/admin/refund-delays/" + receipt.attemptId() + "/investigations"))
                .body(ApiResponse.ok(receipt));
    }
}
