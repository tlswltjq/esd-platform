package com.stove.catalog.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name="catalog_sale_change") @Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class CatalogSaleChange {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,updatable=false) private Long productId;
    @Column(nullable=false,updatable=false,length=100) private String actorSubject;
    @Column(nullable=false,updatable=false,length=20) private String authority;
    @Column(nullable=false,updatable=false,length=20) private String previousSellerStatus;
    @Column(nullable=false,updatable=false,length=20) private String sellerStatus;
    @Column(nullable=false,updatable=false) private boolean previousRestriction;
    @Column(nullable=false,updatable=false) private boolean platformRestricted;
    @Column(nullable=false,updatable=false) private long previousVersion;
    @Column(nullable=false,updatable=false) private long catalogVersion;
    @Column(nullable=false,updatable=false) private Instant changedAt;

    public static CatalogSaleChange record(CatalogProduct product, String actor, String authority,
            String previousStatus, boolean previousRestriction, long previousVersion, Instant now) {
        CatalogSalesValues.requireActor(actor);
        if (!("CREATOR".equals(authority) || "ADMIN".equals(authority)) || product.getCatalogVersion()!=previousVersion+1)
            throw new IllegalArgumentException("Invalid sale change audit");
        CatalogSaleChange result=new CatalogSaleChange();
        result.productId=product.getId(); result.actorSubject=actor; result.authority=authority;
        result.previousSellerStatus=previousStatus; result.sellerStatus=product.getSellerSaleStatus();
        result.previousRestriction=previousRestriction; result.platformRestricted=product.isPlatformRestricted();
        result.previousVersion=previousVersion; result.catalogVersion=product.getCatalogVersion(); result.changedAt=now;
        return result;
    }
}
