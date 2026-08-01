package com.stove.catalog.application;

import com.stove.catalog.api.dto.ProductResponse;
import com.stove.catalog.api.dto.QuoteRequest;
import com.stove.catalog.api.dto.QuoteResponse;
import com.stove.catalog.domain.Product;
import com.stove.catalog.domain.ProductRepository;
import com.stove.catalog.domain.ProductStatus;
import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.OrderLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 읽기 트래픽이 집중되는 경로. 상품 단건은 Redis 캐시로 흡수하고,
 * 캐시 무효화는 상태 변경 지점({@link ProductCommandService})에서만 수행한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductQueryService {

    private final ProductRepository productRepository;

    @Cacheable(cacheNames = "catalog:product", key = "#productId")
    public ProductResponse getProduct(Long productId) {
        return productRepository.findById(productId)
                .map(ProductResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    public Page<ProductResponse> getOnSaleProducts(Pageable pageable) {
        return productRepository.findByStatus(ProductStatus.ON_SALE, pageable)
                .map(ProductResponse::from);
    }

    /**
     * 검증 게이트 1단계: 주문 금액을 서버 가격으로 재계산한다.
     * 판매 불가 상품이 섞여 있으면 여기서 주문 자체가 성립하지 않는다.
     */
    public QuoteResponse quote(QuoteRequest request) {
        List<Long> productIds = request.items().stream().map(QuoteRequest.Item::productId).distinct().toList();
        Map<Long, Product> products = productRepository.findByIdIn(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        List<OrderLine> lines = new ArrayList<>();
        long total = 0;
        String currency = null;
        for (QuoteRequest.Item item : request.items()) {
            Product product = products.get(item.productId());
            if (product == null) {
                throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "productId=" + item.productId());
            }
            product.requirePurchasable();
            if (currency != null && !currency.equals(product.getCurrency())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "통화가 다른 상품은 함께 주문할 수 없습니다.");
            }
            currency = product.getCurrency();
            OrderLine line = new OrderLine(product.getId(), product.getName(), product.getSellerId(),
                    product.getPrice(), item.quantity());
            lines.add(line);
            total += line.lineAmount();
        }
        return new QuoteResponse(lines, total, currency);
    }
}
