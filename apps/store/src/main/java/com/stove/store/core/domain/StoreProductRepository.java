package com.stove.store.core.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreProductRepository extends JpaRepository<StoreProduct, Long>, JpaSpecificationExecutor<StoreProduct> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from StoreProduct p where p.productId = :id")
    Optional<StoreProduct> lockById(@Param("id") Long id);
    Optional<StoreProduct> findByProductCode(String code);
}
