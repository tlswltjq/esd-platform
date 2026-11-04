package com.stove.store.core.service;

import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.store.core.domain.StoreProduct;
import com.stove.store.core.domain.StoreProductRepository;
import com.stove.store.core.domain.StoreProductValues;
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
        StoreProductValues.requireSnapshot(snapshot);
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

}
