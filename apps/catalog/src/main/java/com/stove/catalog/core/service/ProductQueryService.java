package com.stove.catalog.core.service;

import com.stove.catalog.core.domain.Product;
import com.stove.catalog.core.domain.ProductRepository;
import com.stove.catalog.core.domain.ProductSort;
import com.stove.catalog.core.domain.ProductStatus;
import com.stove.catalog.core.domain.ProductView;
import com.stove.catalog.core.domain.Quote;
import com.stove.catalog.core.domain.QuoteItem;
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
 * 읽기 트래픽이 집중되는 경로. 캐시 무효화는 {@link ProductCommandService} 에서만 한다.
 * docs/code-notes.md
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductQueryService {

    private final ProductRepository productRepository;

    @Cacheable(cacheNames = "catalog:product", key = "#productId")
    public ProductView getProduct(Long productId) {
        return productRepository.findById(productId)
                .map(ProductView::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    /**
     * {@code productCode} 로 상품을 찾는다 — 서비스 사이의 자연 키는 내부 id 가 아니다.
     * <b>캐시를 걸지 않는다</b>(무효화 지점이 둘로 늘어난다). docs/code-notes.md
     */
    public ProductView getProductByCode(String productCode) {
        return productRepository.findByProductCode(productCode)
                .map(ProductView::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND,
                        "productCode=" + productCode));
    }

    /**
     * 판매중 상품 목록. <b>정렬을 주지 않은 요청도 {@link ProductSort} 를 통과해야 한다</b> —
     * 거기서 {@code id desc} 를 받는다. [D-024] [D-025] docs/code-notes.md
     */
    public Page<ProductView> getOnSaleProducts(Pageable pageable) {
        return productRepository.findByStatus(ProductStatus.ON_SALE, ProductSort.apply(pageable))
                .map(ProductView::from);
    }

    /**
     * 검증 게이트 1단계: 주문 금액을 서버 가격으로 재계산한다.
     * 판매 불가 상품이 섞여 있으면 여기서 주문 자체가 성립하지 않는다.
     */
    public Quote quote(List<QuoteItem> items) {
        List<Long> productIds = items.stream().map(QuoteItem::productId).distinct().toList();
        Map<Long, Product> products = productRepository.findByIdIn(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        List<OrderLine> lines = new ArrayList<>();
        long total = 0;
        String currency = null;
        for (QuoteItem item : items) {
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
        return new Quote(lines, total, currency);
    }
}
