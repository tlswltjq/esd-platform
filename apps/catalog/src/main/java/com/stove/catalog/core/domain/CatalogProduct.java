package com.stove.catalog.core.domain;

import com.stove.common.kafka.contract.payload.ProjectSnapshot;

import jakarta.persistence.*;

import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "catalog_product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CatalogProduct {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long projectId;

    @Column(nullable = false, unique = true, length = 50)
    private String productCode;

    @Column(nullable = false, length = 100)
    private String ownerSubject;

    @Column(nullable = false)
    private Long currentReleaseRequestId;

    @Column(nullable = false)
    private Instant publishedAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public static CatalogProduct publish(ProjectSnapshot snapshot, Long requestId, Instant now) {
        CatalogProduct product = new CatalogProduct();
        product.projectId = snapshot.projectId();
        product.productCode = snapshot.productCode();
        product.ownerSubject = snapshot.ownerSubject();
        product.publishedAt = now;
        product.replace(requestId, now);
        return product;
    }

    public void requireIdentity(ProjectSnapshot snapshot) {
        if (!projectId.equals(snapshot.projectId())
                || !productCode.equals(snapshot.productCode())
                || !ownerSubject.equals(snapshot.ownerSubject()))
            throw new IllegalArgumentException(
                    "Project identity does not match the published product");
    }

    public void replace(Long requestId, Instant now) {
        currentReleaseRequestId = requestId;
        updatedAt = now;
    }
}
