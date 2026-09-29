package com.stove.settlement.api.controller;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import com.stove.common.core.response.ApiResponse;
import com.stove.settlement.api.application.SettlementCloseFacade;
import com.stove.settlement.core.service.ReconciliationService;
import com.stove.settlement.core.domain.ReconciliationResult;
import com.stove.settlement.api.application.CreatorWorkspaceClient;
import com.stove.settlement.api.controller.dto.SellerSettlementResponse;
import com.stove.settlement.api.controller.dto.SettlementRecordResponse;
import com.stove.settlement.core.service.SellerSettlementService;
import com.stove.settlement.core.service.SettlementRecordService;
import java.time.YearMonth;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 정산 담당자/판매자용 조회 + 수동 마감 API. */
@RestController
@SecurityRequirement(name = "oauth2", scopes = "commerce")
@RequiredArgsConstructor
@RequestMapping("/api/v1/settlements")
public class SettlementController {

    private final SettlementRecordService settlementRecordService;
    private final SellerSettlementService sellerSettlementService;
    private final SettlementCloseFacade settlementCloseFacade;
    private final ReconciliationService reconciliationService;
    private final CreatorWorkspaceClient workspaces;

    @GetMapping("/me/ledger")
    public ApiResponse<List<SettlementRecordResponse>> myLedger(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @AuthenticationPrincipal Jwt jwt) {
        Long sellerId = workspaces.ownWorkspace(jwt.getTokenValue());
        return ApiResponse.ok(settlementRecordService.findBySeller(sellerId, month).stream()
                .map(SettlementRecordResponse::from).toList());
    }

    @GetMapping("/me/closings")
    public ApiResponse<List<SellerSettlementResponse>> myClosings(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @AuthenticationPrincipal Jwt jwt) {
        Long sellerId = workspaces.ownWorkspace(jwt.getTokenValue());
        return ApiResponse.ok(sellerSettlementService.findClosed(month).stream()
                .filter(s -> s.getSellerId().equals(sellerId))
                .map(SellerSettlementResponse::from).toList());
    }

    @GetMapping("/reconciliation")
    public ApiResponse<List<ReconciliationResult>> reconciliation(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return ApiResponse.ok(reconciliationService.month(month));
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    public ResponseEntity<String> csv(@RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=settlement-" + month + ".csv")
                .body(reconciliationService.csv(month));
    }

    /** 주문 단위 원장(매출 + 환불 역산) */
    @GetMapping("/orders/{orderNo}")
    public ApiResponse<List<SettlementRecordResponse>> byOrder(@PathVariable String orderNo) {
        return ApiResponse.ok(settlementRecordService.findByOrder(orderNo).stream()
                .map(SettlementRecordResponse::from)
                .toList());
    }

    /** 판매자 월별 원장 */
    @GetMapping("/sellers/{sellerId}")
    public ApiResponse<List<SettlementRecordResponse>> bySeller(
            @PathVariable Long sellerId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return ApiResponse.ok(settlementRecordService.findBySeller(sellerId, month).stream()
                .map(SettlementRecordResponse::from)
                .toList());
    }

    /** 월 마감 확정본 조회 */
    @GetMapping("/closings")
    public ApiResponse<List<SellerSettlementResponse>> closings(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return ApiResponse.ok(sellerSettlementService.findClosed(month).stream()
                .map(SellerSettlementResponse::from)
                .toList());
    }

    /** 수동 마감(배치 재실행용). 이미 마감된 판매자는 건너뛴다. */
    @PostMapping("/close")
    public ApiResponse<List<SellerSettlementResponse>> close(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return ApiResponse.ok(settlementCloseFacade.closeMonth(month).stream()
                .map(SellerSettlementResponse::from)
                .toList());
    }
}
