package com.stove.catalog.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.catalog.core.domain.*;
import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.common.web.error.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReadPublishedBuild {
    private final CatalogProductRepository products;
    private final CatalogReleaseRepository releases;
    private final ObjectMapper mapper;

    public PublishedBuild execute(String code) {
        if (code == null || !code.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}"))
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        CatalogProduct product = products.findByProductCode(code).filter(p -> code.equals(p.getProductCode()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        CatalogRelease release = releases.findById(product.getCurrentReleaseRequestId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
        try {
            ProjectSnapshot snapshot = mapper.readValue(release.getSnapshot(), ProjectSnapshot.class);
            requirePublishedIdentity(product, release, snapshot);
            return new PublishedBuild(product.getId(), code, release.getReleaseRequestId(), snapshot.contentVersion(),
                    snapshot.buildId(), snapshot.version(), snapshot.fileSize(), snapshot.checksum(), snapshot.storagePath());
        } catch (JsonProcessingException failure) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private static void requirePublishedIdentity(CatalogProduct product, CatalogRelease release, ProjectSnapshot snapshot) {
        if (!"COMPLETED".equals(release.getStatus()) || !product.getProjectId().equals(release.getProjectId())
                || !product.getProductCode().equals(release.getProductCode()) || snapshot == null
                || !product.getProjectId().equals(snapshot.projectId())
                || !product.getProductCode().equals(snapshot.productCode())
                || !product.getOwnerSubject().equals(snapshot.ownerSubject())
                || release.getContentVersion() != snapshot.contentVersion())
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
    }
}
