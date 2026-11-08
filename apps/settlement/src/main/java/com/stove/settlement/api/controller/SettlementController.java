package com.stove.settlement.api.controller;

import com.stove.common.web.response.ApiResponse;
import com.stove.settlement.api.controller.dto.AdminMonthResponse;
import com.stove.settlement.api.controller.dto.AdminSaleResponse;
import com.stove.settlement.api.controller.dto.AdminRefundResponse;
import com.stove.settlement.api.controller.dto.AdminRefundSummaryResponse;
import com.stove.settlement.api.controller.dto.RefundResponse;
import com.stove.settlement.core.service.ReadRefunds;
import com.stove.settlement.api.controller.dto.MonthResponse;
import com.stove.settlement.api.controller.dto.PgReceiptResponse;
import com.stove.settlement.api.controller.dto.PgTransactionResponse;
import com.stove.settlement.core.service.ReadPgFinancials;
import com.stove.settlement.core.service.SynchronizePgTransaction;
import com.stove.settlement.api.controller.dto.SaleResponse;
import com.stove.settlement.api.controller.dto.SettlementPage;
import com.stove.settlement.core.domain.SellerMonthBalance;
import com.stove.settlement.core.domain.SellerMonthKey;
import com.stove.settlement.core.domain.SellerMonthView;
import com.stove.settlement.core.domain.SellerMonthlySettlement;
import com.stove.settlement.core.domain.SettlementQuery;
import com.stove.settlement.core.service.CloseSellerMonth;
import com.stove.settlement.core.service.ReadPgReceipts;
import com.stove.settlement.core.service.ReadSellerBalances;
import com.stove.settlement.core.service.ReceivePgSettlement;
import com.stove.settlement.core.service.ReconcilePgReceipt;
import com.stove.settlement.core.service.SearchSales;
import com.stove.settlement.core.service.SearchSellerMonths;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
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
    private final ReadPgFinancials financials;
    private final SynchronizePgTransaction refresh;
    private final ReadRefunds refunds;
    private final ReadSellerBalances balances;

    @GetMapping("/me/refunds")
    public ApiResponse<SettlementPage<RefundResponse>> myRefunds(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required=false) String month,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(SettlementPage.from(refunds.execute(new SettlementQuery(jwt.getSubject(),month,page,size)).map(RefundResponse::from)));
    }
    @GetMapping("/refunds")
    public ApiResponse<SettlementPage<AdminRefundSummaryResponse>> refunds(@RequestParam(required=false) String sellerSubject,
            @RequestParam(required=false) String month,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(SettlementPage.from(refunds.execute(new SettlementQuery(sellerSubject,month,page,size)).map(AdminRefundSummaryResponse::from)));
    }
    @GetMapping("/refunds/{refundId}")
    public ApiResponse<AdminRefundResponse> refund(@PathVariable String refundId) { return ApiResponse.ok(AdminRefundResponse.from(refunds.execute(refundId))); }

    @GetMapping("/me/sales")
    public ApiResponse<SettlementPage<SaleResponse>> mySales(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required=false) String month,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(SettlementPage.from(sales.execute(new SettlementQuery(jwt.getSubject(),month,page,size)).map(SaleResponse::from)));
    }
    @GetMapping("/me/months")
    public ApiResponse<SettlementPage<MonthResponse>> myMonths(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required=false) String month,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        Page<SellerMonthlySettlement> found=months.execute(new SettlementQuery(jwt.getSubject(),month,page,size));
        Map<SellerMonthKey,SellerMonthBalance> balance=balances.of(found.getContent());
        return ApiResponse.ok(SettlementPage.from(found.map(m -> MonthResponse.from(m,balance.get(key(m))))));
    }
    @GetMapping("/sales")
    public ApiResponse<SettlementPage<AdminSaleResponse>> sales(@RequestParam(required=false) String sellerSubject,
            @RequestParam(required=false) String month,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(SettlementPage.from(sales.executeWithPgData(new SettlementQuery(sellerSubject,month,page,size)).map(AdminSaleResponse::from)));
    }
    @GetMapping("/months")
    public ApiResponse<SettlementPage<AdminMonthResponse>> months(@RequestParam(required=false) String sellerSubject,
            @RequestParam(required=false) String month,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        Page<SellerMonthView> found=months.executeWithPgData(new SettlementQuery(sellerSubject,month,page,size));
        List<SellerMonthlySettlement> rows=found.getContent().stream().map(SellerMonthView::month).toList();
        Map<SellerMonthKey,SellerMonthBalance> balance=balances.of(rows);
        return ApiResponse.ok(SettlementPage.from(found.map(view -> AdminMonthResponse.from(view,balance.get(key(view.month()))))));
    }
    @PostMapping("/sellers/{sellerSubject}/months/{month}/close")
    public ApiResponse<AdminMonthResponse> close(@PathVariable String sellerSubject,@PathVariable String month) {
        SellerMonthlySettlement closed=close.execute(sellerSubject,month);
        return ApiResponse.ok(AdminMonthResponse.from(financials.month(closed),balances.of(closed)));
    }
    private static SellerMonthKey key(SellerMonthlySettlement month) { return new SellerMonthKey(month.getSellerSubject(),month.getSettlementMonth()); }
    @PostMapping("/pg-transactions/{paymentId}/refresh")
    public ApiResponse<PgTransactionResponse> refresh(@PathVariable String paymentId,@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(PgTransactionResponse.from(refresh.execute(paymentId,jwt.getSubject())));
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
