package com.stove.catalog.core.service;

import com.stove.catalog.core.domain.Product;
import com.stove.catalog.core.domain.ProductRepository;
import com.stove.catalog.core.domain.Promotion;
import com.stove.catalog.core.domain.PromotionRepository;
import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.PromotionWindow;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PromotionService {
    private final ProductRepository products;
    private final PromotionRepository promotions;
    private final ProductCommandService productCommands;
    private final AuditLogService audit;

    @Transactional
    public Promotion create(Long productId, Long sellerId, Promotion.Bearer bearer,
                            long discount, Instant start, Instant end, String actor) {
        Product product = products.lockById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        requireOwner(product, sellerId, bearer, actor);
        if (!"KRW".equals(product.getCurrency()) || discount < 1 || discount >= product.getPrice()
                || start == null || end == null || !start.isBefore(end) || !end.isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "KRW 정액 할인, 유효한 기간, 1원 이상 청구액이 필요합니다.");
        }
        if (promotions.findByProductIdAndStoppedAtIsNull(productId).stream()
                .anyMatch(existing -> existing.overlaps(start, end))) {
            throw new BusinessException(ErrorCode.CONFLICT, "겹치는 상품 행사가 있습니다.");
        }
        Promotion promotion = promotions.save(Promotion.schedule(productId, product.getSellerId(),
                discount, bearer, start, end));
        products.flush();
        productCommands.promotionChanged(product);
        audit.record(actor, "PROMOTION_CREATED", promotion.getId(), "productId=" + productId);
        return promotion;
    }

    @Transactional
    public Promotion stop(Long promotionId, Long sellerId, Promotion.Bearer bearer, String actor) {
        Promotion promotion = promotions.findById(promotionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        Product product = products.lockById(promotion.getProductId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        requireOwner(product, sellerId, bearer, actor);
        if (promotion.getBearer() != bearer) {
            audit.recordDenied(actor, promotionId, "wrong-bearer");
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        promotion.stop(Instant.now());
        productCommands.promotionChanged(product);
        audit.record(actor, "PROMOTION_STOPPED", promotionId, "productId=" + product.getId());
        return promotion;
    }

    @Transactional(readOnly = true)
    public List<Promotion> list(Long productId, Long sellerId, boolean admin, String actor) {
        Product product = products.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        if (!admin && !product.getSellerId().equals(sellerId)) {
            audit.recordDenied(actor, productId, "other-seller-list");
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return promotions.findByProductIdOrderByStartsAtDesc(productId);
    }

    @Transactional(readOnly = true)
    public PromotionWindow active(Long productId, Instant now) {
        return promotions.findByProductIdAndStoppedAtIsNull(productId).stream()
                .filter(p -> p.activeAt(now)).findFirst().map(Promotion::window).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<PromotionWindow> windows(Long productId) {
        return promotions.findByProductIdAndStoppedAtIsNull(productId).stream()
                .map(Promotion::window).toList();
    }

    private void requireOwner(Product product, Long sellerId, Promotion.Bearer bearer, String actor) {
        if (bearer == Promotion.Bearer.SELLER && !product.getSellerId().equals(sellerId)) {
            audit.recordDenied(actor, product.getId(), "other-seller");
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
