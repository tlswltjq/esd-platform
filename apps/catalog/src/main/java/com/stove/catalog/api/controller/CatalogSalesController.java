package com.stove.catalog.api.controller;

import com.stove.catalog.core.domain.CatalogSalesView;
import com.stove.catalog.core.domain.PlatformSaleRestriction;
import com.stove.catalog.core.domain.SellerSaleChange;
import com.stove.catalog.core.service.ChangePlatformSaleRestriction;
import com.stove.catalog.core.service.ChangeSellerSaleStatus;
import com.stove.catalog.core.service.ReadCatalogSales;
import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequiredArgsConstructor
public class CatalogSalesController {
    private final ChangeSellerSaleStatus seller;
    private final ChangePlatformSaleRestriction platform;
    private final ReadCatalogSales read;
    @GetMapping("/api/v1/products/by-code/{code}/sales")
    public ResponseEntity<ApiResponse<CatalogSalesView>> seller(@PathVariable String code,@CurrentSubject String actor) {
        return response(read.seller(actor,code));
    }
    @PutMapping("/api/v1/products/by-code/{code}/sales")
    public ResponseEntity<ApiResponse<CatalogSalesView>> seller(@PathVariable String code,@CurrentSubject String actor,@RequestBody SellerRequest request) {
        return response(seller.execute(actor,new SellerSaleChange(code,request.saleStatus(),request.expectedCatalogVersion())));
    }
    @GetMapping("/api/v1/admin/products/{code}/sales/restriction")
    public ResponseEntity<ApiResponse<CatalogSalesView>> platform(@PathVariable String code) { return response(read.platform(code)); }
    @PutMapping("/api/v1/admin/products/{code}/sales/restriction")
    public ResponseEntity<ApiResponse<CatalogSalesView>> platform(@PathVariable String code,@CurrentSubject String actor,@Valid @RequestBody PlatformRequest request) {
        return response(platform.execute(actor,new PlatformSaleRestriction(code,request.restricted(),request.expectedCatalogVersion())));
    }
    private ResponseEntity<ApiResponse<CatalogSalesView>> response(CatalogSalesView view) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(view));
    }
    public record SellerRequest(String saleStatus,long expectedCatalogVersion) {}
    public record PlatformRequest(@NotNull Boolean restricted,long expectedCatalogVersion) {}
}
