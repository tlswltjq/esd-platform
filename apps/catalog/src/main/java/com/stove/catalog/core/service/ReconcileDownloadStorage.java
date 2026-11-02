package com.stove.catalog.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.catalog.core.domain.CatalogProduct;
import com.stove.catalog.core.domain.CatalogProductRepository;
import com.stove.catalog.core.domain.CatalogRelease;
import com.stove.catalog.core.domain.CatalogReleaseRepository;
import com.stove.catalog.core.domain.DownloadBuildReference;
import com.stove.catalog.core.port.BuildDistribution;
import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReconcileDownloadStorage {
    private final CatalogProductRepository products;
    private final CatalogReleaseRepository releases;
    private final BuildDistribution distribution;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<String> due() {
        return products.findDownloadStorageDue(clock.instant(), PageRequest.of(0, 20));
    }

    // Serialize desired-state changes, publication and storage mutations for this product.
    // Storage calls have finite timeouts; a crash/timeout is repaired by durable rechecking.
    @Transactional
    public void execute(String code) {
        CatalogProduct product = products.lockByProductCode(code).orElseThrow();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (product.getDownloadRestrictionVersion() <= 1 || product.getDownloadStorageNextAttemptAt().isAfter(now)) return;
        try {
            Set<DownloadBuildReference> references = new LinkedHashSet<>();
            for (CatalogRelease release : releases.findByProjectIdAndStatusOrderByReleaseRequestIdAsc(product.getProjectId(), "COMPLETED")) {
                ProjectSnapshot snapshot = mapper.readValue(release.getSnapshot(), ProjectSnapshot.class);
                if (snapshot == null || !product.getProjectId().equals(snapshot.projectId())
                        || !product.getProductCode().equals(snapshot.productCode())
                        || !product.getOwnerSubject().equals(snapshot.ownerSubject())
                        || !release.getProductCode().equals(snapshot.productCode())
                        || release.getContentVersion() != snapshot.contentVersion())
                    throw new IllegalStateException("Invalid published build identity");
                references.add(new DownloadBuildReference(snapshot.projectId(), snapshot.storagePath()));
            }
            if (references.isEmpty()) throw new IllegalStateException("No published builds");
            distribution.apply(List.copyOf(references), product.isDownloadRestricted());
            product.downloadStorageApplied(clock.instant().truncatedTo(ChronoUnit.MICROS));
        } catch (JsonProcessingException | RuntimeException failure) {
            // Do not persist provider messages, URLs or credentials as public operational errors.
            String detail = failure.getMessage() != null && Set.of("Invalid published build identity", "No published builds", "No object tag capacity",
                    "Object restriction was not applied", "Storage read restriction mismatch").contains(failure.getMessage())
                    ? failure.getMessage() : "Provider or evidence operation failed";
            log.warn("Download storage reconciliation failed: product={} type={} detail={}", code, failure.getClass().getSimpleName(), detail);
            product.downloadStorageFailed(clock.instant().truncatedTo(ChronoUnit.MICROS));
        }
    }
}
