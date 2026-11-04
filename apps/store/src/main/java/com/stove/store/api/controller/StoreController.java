package com.stove.store.api.controller;

import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.common.web.response.ApiResponse;
import com.stove.store.api.controller.dto.StoreProductDetail;
import com.stove.store.api.controller.dto.StoreProductPage;
import com.stove.store.core.service.ReadStoreProduct;
import com.stove.store.core.service.SearchStoreProducts;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
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
@RequestMapping("/api/v1/storefront/products")
public class StoreController {
    private final SearchStoreProducts searchStoreProducts;
    private final ReadStoreProduct readStoreProduct;
    @GetMapping
    public ResponseEntity<ApiResponse<StoreProductPage>> search(@RequestParam(required = false) String q,
            @RequestParam(defaultValue = "NEWEST") String sort,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) List<String> genres, HttpServletRequest request) {
        if (request.getParameterMap().containsKey("genres") && (genres == null || genres.isEmpty()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "장르 검색 조건이 비어 있습니다.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(StoreProductPage.from(searchStoreProducts.execute(q, sort, page, size, genres))));
    }
    @GetMapping("/{productCode}")
    public ResponseEntity<ApiResponse<StoreProductDetail>> detail(@PathVariable String productCode) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(StoreProductDetail.from(readStoreProduct.execute(productCode))));
    }
}
