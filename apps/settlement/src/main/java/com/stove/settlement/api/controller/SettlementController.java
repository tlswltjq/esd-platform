package com.stove.settlement.api.controller;

import com.stove.common.web.response.ApiResponse;
import com.stove.settlement.api.controller.dto.AdminMonthResponse;
import com.stove.settlement.api.controller.dto.AdminSaleResponse;
import com.stove.settlement.api.controller.dto.MonthResponse;
import com.stove.settlement.api.controller.dto.PgReceiptResponse;
import com.stove.settlement.api.controller.dto.SaleResponse;
import com.stove.settlement.api.controller.dto.SettlementPage;
import com.stove.settlement.core.domain.SettlementQuery;
import com.stove.settlement.core.service.CloseSellerMonth;
import com.stove.settlement.core.service.ReadPgReceipts;
import com.stove.settlement.core.service.ReceivePgSettlement;
import com.stove.settlement.core.service.ReconcilePgReceipt;
import com.stove.settlement.core.service.SearchSales;
import com.stove.settlement.core.service.SearchSellerMonths;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/settlements")
public class SettlementController {
    private final SearchSales sales;
    private final SearchSellerMonths months;
    private final CloseSellerMonth close;
    private final ReceivePgSettlement receive;
    private final ReconcilePgReceipt reconcile;
    private final ReadPgReceipts receipts;

    @GetMapping("/me/sales")
    public ApiResponse<SettlementPage<SaleResponse>> mySales(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required=false) String month,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(SettlementPage.from(sales.execute(new SettlementQuery(jwt.getSubject(),month,page,size)).map(SaleResponse::from)));
    }
    @GetMapping("/me/months")
    public ApiResponse<SettlementPage<MonthResponse>> myMonths(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required=false) String month,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(SettlementPage.from(months.execute(new SettlementQuery(jwt.getSubject(),month,page,size)).map(MonthResponse::from)));
    }
    @GetMapping("/sales")
    public ApiResponse<SettlementPage<AdminSaleResponse>> sales(@RequestParam(required=false) String sellerSubject,
            @RequestParam(required=false) String month,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(SettlementPage.from(sales.execute(new SettlementQuery(sellerSubject,month,page,size)).map(AdminSaleResponse::from)));
    }
    @GetMapping("/months")
    public ApiResponse<SettlementPage<AdminMonthResponse>> months(@RequestParam(required=false) String sellerSubject,
            @RequestParam(required=false) String month,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(SettlementPage.from(months.execute(new SettlementQuery(sellerSubject,month,page,size)).map(AdminMonthResponse::from)));
    }
    @PostMapping("/sellers/{sellerSubject}/months/{month}/close")
    public ApiResponse<AdminMonthResponse> close(@PathVariable String sellerSubject,@PathVariable String month) {
        return ApiResponse.ok(AdminMonthResponse.from(close.execute(sellerSubject,month)));
    }
    public record ReceiptRequest(String providerSettlementId) { }
    @PostMapping("/pg-receipts")
    public ApiResponse<PgReceiptResponse> receive(@RequestBody ReceiptRequest body,@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(PgReceiptResponse.from(receive.execute(body.providerSettlementId(),jwt.getSubject())));
    }
    @GetMapping("/pg-receipts")
    public ApiResponse<SettlementPage<PgReceiptResponse>> receipts(@RequestParam(required=false) String status,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(SettlementPage.from(receipts.execute(status,page,size).map(PgReceiptResponse::from)));
    }
    @GetMapping("/pg-receipts/{providerSettlementId}")
    public ApiResponse<PgReceiptResponse> receipt(@PathVariable String providerSettlementId) {
        return ApiResponse.ok(PgReceiptResponse.from(receipts.execute(providerSettlementId)));
    }
    @PostMapping("/pg-receipts/{providerSettlementId}/reconcile")
    public ApiResponse<PgReceiptResponse> reconcile(@PathVariable String providerSettlementId) {
        return ApiResponse.ok(PgReceiptResponse.from(reconcile.execute(providerSettlementId)));
    }
}
