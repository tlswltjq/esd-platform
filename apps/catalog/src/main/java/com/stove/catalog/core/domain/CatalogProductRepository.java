package com.stove.catalog.core.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CatalogProductRepository extends JpaRepository<CatalogProduct, Long> {
    @Query("select p from CatalogProduct p where p.platformListingHidden = false and (p.listingPreference = :listed or (p.listingPreference = :auto and p.saleStatus = 'ON_SALE' and p.platformRestricted = false))")
    Page<CatalogProduct> findListed(@Param("listed") ListingPreference listed, @Param("auto") ListingPreference auto, Pageable page);
    @Query("select p.productCode from CatalogProduct p where p.downloadRestrictionVersion > 1 and p.downloadStorageNextAttemptAt <= :now order by p.downloadStorageNextAttemptAt, p.id")
    List<String> findDownloadStorageDue(@Param("now") Instant now, Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CatalogProduct p where p.projectId = :projectId")
    Optional<CatalogProduct> lockByProjectId(@Param("projectId") Long projectId);

    Optional<CatalogProduct> findByProductCode(String productCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CatalogProduct p where p.productCode = :code")
    Optional<CatalogProduct> lockByProductCode(@Param("code") String code);
}
