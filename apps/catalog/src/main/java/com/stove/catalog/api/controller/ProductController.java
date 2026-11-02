package com.stove.catalog.api.controller;

import com.stove.catalog.api.controller.dto.ProductDetail;
import com.stove.catalog.api.controller.dto.ProductPage;
import com.stove.catalog.core.service.ReadPublishedProducts;
import com.stove.common.web.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products")
public class ProductController {
    private final ReadPublishedProducts readPublishedProducts;

    @GetMapping
    public ResponseEntity<ApiResponse<ProductPage>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(ProductPage.from(readPublishedProducts.list(page, size))));
    }

    @GetMapping("/by-code/{productCode}")
    public ResponseEntity<ApiResponse<ProductDetail>> detail(@PathVariable String productCode) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(ProductDetail.from(readPublishedProducts.detail(productCode))));
    }
}
