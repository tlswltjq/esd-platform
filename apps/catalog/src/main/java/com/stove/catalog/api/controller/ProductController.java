package com.stove.catalog.api.controller;

import com.stove.catalog.api.controller.dto.ProductResponse;
import com.stove.catalog.api.controller.dto.QuoteRequest;
import com.stove.catalog.api.controller.dto.QuoteResponse;
import com.stove.catalog.core.service.ProductCommandService;
import com.stove.catalog.core.service.ProductQueryService;
import com.stove.common.core.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductQueryService productQueryService;
    private final ProductCommandService productCommandService;

    @GetMapping
    public ApiResponse<List<ProductResponse>> list(@PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(productQueryService.getOnSaleProducts(pageable)
                .map(ProductResponse::from)
                .getContent());
    }

    @GetMapping("/{productId}")
    public ApiResponse<ProductResponse> detail(@PathVariable Long productId) {
        return ApiResponse.ok(ProductResponse.from(productQueryService.getProduct(productId)));
    }

    /** 운영툴용 판매 시작/중지 (실제로는 인증·권한 필터 뒤에 위치) */
    @PostMapping("/{productId}/sale-open")
    public ApiResponse<Void> openSale(@PathVariable Long productId) {
        productCommandService.openSale(productId);
        return ApiResponse.ok();
    }

    @PostMapping("/{productId}/suspend")
    public ApiResponse<Void> suspend(@PathVariable Long productId) {
        productCommandService.suspend(productId);
        return ApiResponse.ok();
    }

    /** 운영툴: store 검색 색인 재구축 트리거 */
    @PostMapping("/reindex")
    public ApiResponse<Integer> reindex() {
        return ApiResponse.ok(productCommandService.republishAll());
    }

    /** 내부 전용: 주문 금액 서버 재계산 (게이트웨이에서 외부 노출 차단) */
    @PostMapping("/quote")
    public ApiResponse<QuoteResponse> quote(@Valid @RequestBody QuoteRequest request) {
        return ApiResponse.ok(QuoteResponse.from(productQueryService.quote(request.toItems())));
    }
}
