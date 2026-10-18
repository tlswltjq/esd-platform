package com.stove.catalog.api.controller;

import com.stove.catalog.core.service.ReadProductOwner;
import com.stove.catalog.core.domain.ProductOwner;
import com.stove.common.web.response.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
public class InternalOwnerController {
    private final ReadProductOwner owners;
    public InternalOwnerController(ReadProductOwner owners) { this.owners=owners; }
    public record OwnerResponse(Long productId, String productCode, String ownerSubject) { }

    @GetMapping("/internal/v1/products/{productCode}/owner")
    public ResponseEntity<ApiResponse<OwnerResponse>> owner(@PathVariable String productCode) {
        ProductOwner owner=owners.execute(productCode);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                ApiResponse.ok(new OwnerResponse(owner.productId(),owner.productCode(),owner.ownerSubject())));
    }
}
