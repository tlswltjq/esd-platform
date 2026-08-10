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
 * 읽기 트래픽이 집중되는 경로. 상품 단건은 Redis 캐시로 흡수하고,
 * 캐시 무효화는 상태 변경 지점({@link ProductCommandService})에서만 수행한다.
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
     * {@code productCode} 로 상품을 찾는다.
     *
     * <p>서비스 사이의 자연 키는 내부 id 가 아니라 {@code productCode} 다 — 이벤트 payload,
     * download 의 상품 참조, store 색인, studio 프로젝트가 모두 이 값으로 말한다.
     * catalog 만 내부 id 로밖에 조회되지 않으면 <b>코드를 아는 쪽이 id 를 얻을 방법이 없다.</b>
     *
     * <p>캐시를 걸지 않는다. {@link #getProduct} 와 키 공간이 달라서, 여기에도 캐시를 두면
     * {@link ProductCommandService} 의 무효화 지점이 둘로 늘어난다. 운영·연동 경로라
     * 읽기 트래픽이 몰리지 않으므로 얻는 것보다 잃는 것이 크다.
     */
    public ProductView getProductByCode(String productCode) {
        return productRepository.findByProductCode(productCode)
                .map(ProductView::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND,
                        "productCode=" + productCode));
    }

    /**
     * 판매중 상품 목록.
     *
     * <p>정렬 키는 저장소에 닿기 전에 {@link ProductSort} 를 통과해야 한다. 그러지 않으면
     * 클라이언트가 보낸 문자열을 Spring Data 가 엔티티 속성으로 해석하다
     * {@code PropertyReferenceException} 을 던지고, 그 예외가 {@code GlobalExceptionHandler} 의
     * 마지막 분기로 흘러 <b>500</b> 이 나간다 — 클라이언트 잘못을 서버 장애로 표시하는
     * D-015·D-020 과 같은 부류다(D-024).
     *
     * <p>컨트롤러가 아니라 여기서 막는 이유는 D-019 와 같다. 어댑터에만 두면 그 경로 하나만
     * 지켜지고, 어댑터는 늘어난다.
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
