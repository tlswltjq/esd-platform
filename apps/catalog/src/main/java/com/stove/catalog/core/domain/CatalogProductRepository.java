package com.stove.catalog.core.domain;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CatalogProductRepository extends JpaRepository<CatalogProduct, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CatalogProduct p where p.projectId = :projectId")
    Optional<CatalogProduct> lockByProjectId(@Param("projectId") Long projectId);

    Optional<CatalogProduct> findByProductCode(String productCode);
}
