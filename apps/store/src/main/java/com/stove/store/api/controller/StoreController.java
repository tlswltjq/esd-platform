package com.stove.store.api.controller;

import com.stove.common.core.response.ApiResponse;
import com.stove.store.api.controller.dto.StoreProductResponse;
import com.stove.store.core.service.StoreService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/storefront")
public class StoreController {

    private final StoreService storeService;

    /** 검색/목록 — 판매 중(ON_SALE) 상품만 노출된다 */
    @GetMapping("/products")
    public ApiResponse<List<StoreProductResponse>> search(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(storeService.search(q, page, size).stream()
                .map(StoreProductResponse::from)
                .toList());
    }

    /** 메인 진열(프로모션) */
    @GetMapping("/featured")
    public ApiResponse<List<StoreProductResponse>> featured() {
        return ApiResponse.ok(storeService.featured().stream()
                .map(StoreProductResponse::from)
                .toList());
    }
}
