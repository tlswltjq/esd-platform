package com.stove.store.api.controller;

import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.common.web.response.ApiResponse;
import com.stove.store.api.controller.dto.StoreProductDetail;
import com.stove.store.api.controller.dto.StoreProductPage;
import com.stove.store.core.service.ReadStoreProduct;
import com.stove.store.core.service.SearchStoreProducts;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/storefront/products")
public class StoreController {
    private final SearchStoreProducts searchStoreProducts;
    private final ReadStoreProduct readStoreProduct;
    @GetMapping
    public ApiResponse<StoreProductPage> search(@RequestParam(required = false) String q,
            @RequestParam(defaultValue = "NEWEST") String sort,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(StoreProductPage.from(searchStoreProducts.execute(q, sort, page, size)));
    }
    @GetMapping("/{productCode}")
    public ApiResponse<StoreProductDetail> detail(@PathVariable String productCode) {
        return ApiResponse.ok(StoreProductDetail.from(readStoreProduct.execute(productCode)));
    }
}
