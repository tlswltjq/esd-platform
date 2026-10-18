package com.stove.store.core.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreProductRepository extends JpaRepository<StoreProduct, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from StoreProduct p where p.productId = :id")
    Optional<StoreProduct> lockById(@Param("id") Long id);
    Optional<StoreProduct> findByProductCode(String code);
    @Query("select p from StoreProduct p where lower(p.title) like :pattern escape '!'")
    Page<StoreProduct> search(@Param("pattern") String pattern, Pageable pageable);
}
