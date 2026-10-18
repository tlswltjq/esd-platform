package com.stove.store.core.service;

import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.store.core.domain.StoreProduct;
import com.stove.store.core.domain.StoreProductRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.LockAcquisitionException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ApplyCatalogProduct {
    public static final String CONSUMER_GROUP = "store-catalog-publication";
    private final StoreProductRepository products;
    private final EntityManager entityManager;
    private final TransactionTemplate transaction;

    public ApplyCatalogProduct(StoreProductRepository products, EntityManager entityManager,
                               PlatformTransactionManager transactions) {
        this.products = products; this.entityManager = entityManager;
        transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public void execute(CatalogProductSnapshot snapshot) {
        validate(snapshot);
        // A first INSERT can race with HTTP initialization or Kafka delivery. Retry a fresh transaction.
        for (int attempt = 0; ; attempt++) {
            try {
                transaction.executeWithoutResult(tx -> {
                    StoreProduct product = products.lockById(snapshot.productId()).orElse(null);
                    if (product == null) {
                        if (products.findByProductCode(snapshot.productCode()).isPresent())
                            throw new BusinessException(ErrorCode.CONFLICT, "상품 코드가 다른 상품에 연결되어 있습니다.");
                        entityManager.persist(StoreProduct.create(snapshot));
                        entityManager.flush();
                    } else product.apply(snapshot);
                });
                return;
            } catch (DataIntegrityViolationException | TransientDataAccessException
                     | ConstraintViolationException
                     | LockAcquisitionException failure) {
                if (attempt == 2) throw failure;
            }
        }
    }

    private static void validate(CatalogProductSnapshot s) {
        if (s == null || s.productId() == null || s.productId() <= 0 || s.contentVersion() <= 0
                || !text(s.productCode(), 50) || !s.productCode().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}")
                || !text(s.title(), 200) || !text(s.shortDescription(), 500)
                || !text(s.thumbnailUrl(), 1000) || !text(s.content(), 100000)
                || (s.videoUrl() != null && s.videoUrl().length() > 1000)
                || !text(s.buildVersion(), 100) || s.price() < 0 || !"KRW".equals(s.currency())
                || s.publishedAt() == null || s.updatedAt() == null)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "공개 상품 데이터가 올바르지 않습니다.");
    }
    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }
}
