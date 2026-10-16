package com.stove.catalog.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.catalog.core.domain.*;
import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.common.web.error.*;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReadPublishedProducts {
    private final CatalogProductRepository products;
    private final CatalogReleaseRepository releases;
    private final ObjectMapper mapper;

    public Page<PublishedProduct> list(int page, int size) {
        if (page < 0 || size < 1 || size > 100)
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST, "page >= 0, 1 <= size <= 100이어야 합니다.");
        Page<CatalogProduct> result =
                products.findAll(
                        PageRequest.of(
                                page, size, Sort.by(Sort.Direction.DESC, "publishedAt", "id")));
        Map<Long, CatalogRelease> snapshots =
                releases
                        .findAllById(
                                result.stream()
                                        .map(CatalogProduct::getCurrentReleaseRequestId)
                                        .toList())
                        .stream()
                        .collect(Collectors.toMap(CatalogRelease::getReleaseRequestId, r -> r));
        return result.map(p -> view(p, snapshots.get(p.getCurrentReleaseRequestId())));
    }

    public PublishedProduct detail(String code) {
        CatalogProduct product =
                products.findByProductCode(code)
                        .filter(p -> p.getProductCode().equals(code))
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return view(product, releases.findById(product.getCurrentReleaseRequestId()).orElseThrow());
    }

    private PublishedProduct view(CatalogProduct product, CatalogRelease release) {
        try {
            return new PublishedProduct(
                    product.getId(),
                    mapper.readValue(release.getSnapshot(), ProjectSnapshot.class),
                    product.getPublishedAt(),
                    product.getUpdatedAt());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 공개 스냅샷을 읽을 수 없습니다.", e);
        }
    }
}
