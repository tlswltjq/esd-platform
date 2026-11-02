package com.stove.catalog.core.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface CatalogReleaseRepository extends JpaRepository<CatalogRelease, Long> {
    List<CatalogRelease> findByProjectIdAndStatusOrderByReleaseRequestIdAsc(Long projectId, String status);
    Optional<CatalogRelease> findByRequestEventId(String requestEventId);

    Optional<CatalogRelease> findFirstByProjectIdAndContentVersionOrderByReleaseRequestIdAsc(
            Long projectId, long contentVersion);
}
