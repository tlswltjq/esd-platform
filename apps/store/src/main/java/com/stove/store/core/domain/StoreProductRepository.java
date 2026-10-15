package com.stove.store.core.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface StoreProductRepository extends JpaRepository<StoreProduct, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from StoreProduct p where p.productId = :id")
    Optional<StoreProduct> lockById(@Param("id") Long id);
    Optional<StoreProduct> findByProductCode(String code);
    @Query("select p from StoreProduct p where lower(p.title) like :pattern escape '!'")
    Page<StoreProduct> search(@Param("pattern") String pattern, Pageable pageable);
}
