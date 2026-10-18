package com.stove.catalog.core.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CatalogProductRepository extends JpaRepository<CatalogProduct, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CatalogProduct p where p.projectId = :projectId")
    Optional<CatalogProduct> lockByProjectId(@Param("projectId") Long projectId);

    Optional<CatalogProduct> findByProductCode(String productCode);
}
