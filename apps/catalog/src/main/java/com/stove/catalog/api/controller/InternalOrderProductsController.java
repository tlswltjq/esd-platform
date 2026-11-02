package com.stove.catalog.api.controller;

import com.stove.catalog.core.domain.PublishedProduct;
import com.stove.catalog.core.domain.ProductPublicAccess;
import com.stove.catalog.core.service.ReadPublishedProducts;
import com.stove.common.web.response.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequiredArgsConstructor
public class InternalOrderProductsController {
    private final ReadPublishedProducts read;
    @GetMapping("/internal/v1/order-products/{code}")
    public ResponseEntity<ApiResponse<OrderProduct>> detail(@PathVariable String code) {
        PublishedProduct product = read.internalDetail(code);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(OrderProduct.from(product)));
    }
    public record OrderProduct(Long productId, String productCode, String title, long contentVersion,
            long price, String currency, String saleStatus, boolean publicDetailAllowed) {
        static OrderProduct from(PublishedProduct p) {
            return new OrderProduct(p.productId(), p.snapshot().productCode(), p.snapshot().title(),
                    p.snapshot().contentVersion(), p.snapshot().price(), p.snapshot().currency(), p.saleStatus(), ProductPublicAccess.allowsDetail(p.listed()));
        }
    }
}
