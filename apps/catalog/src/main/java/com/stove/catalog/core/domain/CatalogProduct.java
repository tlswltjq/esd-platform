package com.stove.catalog.core.domain;

import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

    @Column(nullable = false, length = 20)
    private String saleStatus;

    @Column(nullable = false)
    private long catalogVersion;

    @Column(nullable = false)
    private boolean platformRestricted;

    @Column(nullable = false) private boolean downloadRestricted;
    @Column(nullable = false) private long downloadRestrictionVersion;
    @Column(nullable = false, length = 20) private String downloadStorageStatus;
    private Instant downloadStorageCheckedAt;
    private Instant downloadStorageNextAttemptAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) private ListingPreference listingPreference;
    @Column(nullable = false) private boolean platformListingHidden;

    public boolean isListed() { return !platformListingHidden && listingPreference.isListed(getSaleStatus()); }

    public boolean changeListingPreference(ListingPreference preference, long expectedVersion, Instant now) {
        if (preference == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        requireChange(expectedVersion, now);
        if (listingPreference == preference) return false;
        listingPreference = preference;
        catalogVersion = Math.incrementExact(catalogVersion);
        updatedAt = now;
        return true;
    }

    public boolean changePlatformListingHidden(boolean hidden, long expectedVersion, Instant now) {
        requireChange(expectedVersion, now);
        if (platformListingHidden == hidden) return false;
        platformListingHidden = hidden;
        catalogVersion = Math.incrementExact(catalogVersion);
        updatedAt = now;
        return true;
    }

    public boolean isDownloadUnavailable() {
        return downloadRestricted || !"APPLIED".equals(downloadStorageStatus);
    }

    public boolean changeDownloadRestriction(boolean restricted, long expectedVersion, Instant now) {
        if (expectedVersion != downloadRestrictionVersion)
            throw new BusinessException(ErrorCode.CONFLICT, "다운로드 제한이 변경되었습니다. 현재 상태를 다시 확인하세요.");
        if (now == null || expectedVersion <= 0)
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        if (downloadRestricted == restricted) return false;
        downloadRestricted = restricted;
        downloadRestrictionVersion = Math.incrementExact(downloadRestrictionVersion);
        queueDownloadStorage(now);
        return true;
    }

    public void queueDownloadStorage(Instant now) {
        downloadStorageStatus = "PENDING";
        downloadStorageNextAttemptAt = now;
    }

    public void downloadStorageApplied(Instant now) {
        downloadStorageStatus = "APPLIED";
        downloadStorageCheckedAt = now;
        downloadStorageNextAttemptAt = now.plusSeconds(60);
    }

    public void downloadStorageFailed(Instant now) {
        downloadStorageStatus = "FAILED";
        downloadStorageNextAttemptAt = now.plusSeconds(30);
    }

    public String getSaleStatus() { return platformRestricted ? "BLOCKED" : saleStatus; }
    public String getSellerSaleStatus() { return saleStatus; }

    public static CatalogProduct publish(ProjectSnapshot snapshot, Long requestId, Instant now) {
        CatalogProduct product = new CatalogProduct();
        product.projectId = snapshot.projectId();
        product.productCode = snapshot.productCode();
        product.ownerSubject = snapshot.ownerSubject();
        product.publishedAt = now;
        product.currentReleaseRequestId = requestId;
        product.updatedAt = now;
        product.saleStatus = "ON_SALE";
        product.listingPreference = ListingPreference.AUTO;
        product.downloadRestrictionVersion = 1;
        product.downloadStorageStatus = "APPLIED";
        product.catalogVersion = snapshot.contentVersion();
        return product;
    }

    public void requireIdentity(ProjectSnapshot snapshot) {
        if (!projectId.equals(snapshot.projectId())
                || !productCode.equals(snapshot.productCode())
                || !ownerSubject.equals(snapshot.ownerSubject()))
            throw new IllegalArgumentException(
                    "Project identity does not match the published product");
    }

    public void replace(Long requestId, long contentVersion, Instant now) {
        if (downloadRestrictionVersion > 1) queueDownloadStorage(now);
        currentReleaseRequestId = requestId;
        catalogVersion = Math.max(Math.incrementExact(catalogVersion), contentVersion);
        updatedAt = now;
    }

    public boolean changeSaleStatus(String target, long expectedVersion, Instant now) {
        if (!"ON_SALE".equals(target) && !"STOPPED".equals(target))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "판매 상태 변경 입력이 올바르지 않습니다.");
        requireChange(expectedVersion, now);
        if (saleStatus.equals(target)) return false;
        saleStatus = target;
        catalogVersion = Math.incrementExact(catalogVersion);
        updatedAt = now;
        return true;
    }

    public boolean changePlatformRestriction(boolean restricted, long expectedVersion, Instant now) {
        requireChange(expectedVersion, now);
        if (platformRestricted == restricted) return false;
        platformRestricted = restricted;
        catalogVersion = Math.incrementExact(catalogVersion);
        updatedAt = now;
        return true;
    }

    private void requireChange(long expectedVersion, Instant now) {
        if (expectedVersion <= 0 || now == null || now.getNano() % 1000 != 0 || now.isBefore(updatedAt))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "판매 상태 변경 입력이 올바르지 않습니다.");
        if (expectedVersion != catalogVersion)
            throw new BusinessException(ErrorCode.CONFLICT, "상품 상태가 변경되었습니다. 현재 상품을 다시 확인하세요.");
    }
}
