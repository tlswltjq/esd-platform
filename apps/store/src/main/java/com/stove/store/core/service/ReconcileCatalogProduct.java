package com.stove.store.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.store.core.domain.CanonicalProduct;
import com.stove.store.core.domain.ReconciliationResult;
import com.stove.store.core.domain.StoreProduct;
import com.stove.store.core.domain.StoreProductRepository;
import com.stove.store.core.domain.StoreProductValues;
import com.stove.store.core.domain.StoreReconciliation;
import com.stove.store.core.domain.StoreReconciliationRepository;
import com.stove.store.core.port.CatalogProducts;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@RequiredArgsConstructor
public class ReconcileCatalogProduct {
    private final CatalogProducts catalog;
    private final StoreProductRepository products;
    private final StoreReconciliationRepository reconciliations;
    private final EntityManager entityManager;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public ReconciliationResult execute(String code) {
        StoreProductValues.requireCode(code);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Catalog lookup requires no active Store transaction");
        CanonicalProduct canonical = new CanonicalProduct(code, catalog.detail(code));
        Instant sourceReadAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        for (int attempt = 0; ; attempt++) {
            try {
                return Objects.requireNonNull(transaction.execute(status -> apply(canonical, sourceReadAt)));
            } catch (RuntimeException failure) {
                // flush의 교착·중복 키는 jakarta.persistence 예외로 감싸여 온다(D-047). 원인 사슬로 판단한다.
                if (attempt == 2 || !RetryableWriteFailure.retryable(failure)) throw failure;
            }
        }
    }

    private ReconciliationResult apply(CanonicalProduct canonical, Instant sourceReadAt) {
        StoreProduct product = products.lockById(canonical.snapshot().productId()).orElse(null);
        CatalogProductSnapshot before = product == null ? null : product.snapshot();
        ReconciliationResult result;
        if (product == null) {
            StoreProduct sameCode = products.findByProductCode(canonical.requestedCode()).orElse(null);
            if (sameCode != null) {
                if (sameCode.getProductId().equals(canonical.snapshot().productId()))
                    throw new ConcurrencyFailureException("같은 상품이 동시에 생성되었습니다. 새 트랜잭션에서 재시도합니다.");
                throw new BusinessException(ErrorCode.CONFLICT, "상품 코드가 다른 상품에 연결되어 있습니다.");
            }
            product = StoreProduct.create(canonical.snapshot());
            entityManager.persist(product);
            entityManager.flush();
            result = ReconciliationResult.INSERTED;
        } else result = product.reconcile(canonical);
        if (result.changed()) {
            reconciliations.saveAndFlush(StoreReconciliation.record(canonical, result, before == null ? null : json(before),
                    json(product.snapshot()), sourceReadAt, clock.instant().truncatedTo(ChronoUnit.MICROS)));
        }
        return result;
    }

    private String json(CatalogProductSnapshot snapshot) {
        try { return mapper.writeValueAsString(snapshot); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("사본 교정 근거를 저장할 수 없습니다.", failure); }
    }
}
