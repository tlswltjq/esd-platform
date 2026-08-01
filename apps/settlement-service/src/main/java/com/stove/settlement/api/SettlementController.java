package com.stove.settlement.api;

import com.stove.common.core.response.ApiResponse;
import com.stove.settlement.api.dto.SellerSettlementResponse;
import com.stove.settlement.api.dto.SettlementRecordResponse;
import com.stove.settlement.application.SettlementService;
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

    private final SettlementService settlementService;

    /** 주문 단위 원장(매출 + 환불 역산) */
    @GetMapping("/orders/{orderNo}")
    public ApiResponse<List<SettlementRecordResponse>> byOrder(@PathVariable String orderNo) {
        return ApiResponse.ok(settlementService.getRecords(orderNo).stream()
                .map(SettlementRecordResponse::from)
                .toList());
    }

    /** 판매자 월별 원장 */
    @GetMapping("/sellers/{sellerId}")
    public ApiResponse<List<SettlementRecordResponse>> bySeller(
            @PathVariable Long sellerId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return ApiResponse.ok(settlementService.getSellerRecords(sellerId, month).stream()
                .map(SettlementRecordResponse::from)
                .toList());
    }

    /** 월 마감 확정본 조회 */
    @GetMapping("/closings")
    public ApiResponse<List<SellerSettlementResponse>> closings(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return ApiResponse.ok(settlementService.getClosedSettlements(month).stream()
                .map(SellerSettlementResponse::from)
                .toList());
    }

    /** 수동 마감(배치 재실행용). 이미 마감된 판매자는 건너뛴다. */
    @PostMapping("/close")
    public ApiResponse<List<SellerSettlementResponse>> close(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return ApiResponse.ok(settlementService.closeMonth(month).stream()
                .map(SellerSettlementResponse::from)
                .toList());
    }
}
