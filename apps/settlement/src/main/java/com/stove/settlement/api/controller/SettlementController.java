package com.stove.settlement.api.controller;

import com.stove.common.core.response.ApiResponse;
import com.stove.settlement.api.application.SettlementCloseFacade;
import com.stove.settlement.api.controller.dto.SellerSettlementResponse;
import com.stove.settlement.api.controller.dto.SettlementRecordResponse;
import com.stove.settlement.core.service.SellerSettlementService;
import com.stove.settlement.core.service.SettlementRecordService;
import java.time.YearMonth;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 정산 담당자/판매자용 조회 + 수동 마감 API. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/settlements")
public class SettlementController {

    private final SettlementRecordService settlementRecordService;
    private final SellerSettlementService sellerSettlementService;
    private final SettlementCloseFacade settlementCloseFacade;

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
