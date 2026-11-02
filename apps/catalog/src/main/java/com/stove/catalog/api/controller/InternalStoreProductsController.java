package com.stove.catalog.api.controller;

import com.stove.catalog.api.controller.dto.ProductPage;
import com.stove.catalog.api.controller.dto.ProductDetail;
import org.springframework.web.bind.annotation.PathVariable;
import com.stove.catalog.core.service.ReadPublishedProducts;
import com.stove.common.web.response.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequiredArgsConstructor
public class InternalStoreProductsController {
    private final ReadPublishedProducts read;
    @GetMapping("/internal/v1/store-products/{code}")
    public ResponseEntity<ApiResponse<ProductDetail>> detail(@PathVariable String code) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(ProductDetail.from(read.internalDetail(code))));
    }
    @GetMapping("/internal/v1/store-products")
    public ResponseEntity<ApiResponse<ProductPage>> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(ProductPage.from(read.synchronizationPage(page, size))));
    }
}
