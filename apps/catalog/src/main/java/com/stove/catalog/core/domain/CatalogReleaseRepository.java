package com.stove.catalog.core.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CatalogReleaseRepository extends JpaRepository<CatalogRelease, Long> {
    Optional<CatalogRelease> findByRequestEventId(String requestEventId);

    Optional<CatalogRelease> findFirstByProjectIdAndContentVersionOrderByReleaseRequestIdAsc(
            Long projectId, long contentVersion);
}
