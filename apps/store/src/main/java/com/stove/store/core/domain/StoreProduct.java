package com.stove.store.core.domain;

import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "store_product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreProduct {
    @Id private Long productId;
    @Column(nullable = false, unique = true, length = 50) private String productCode;
    @Column(nullable = false, length = 200) private String title;
    @Column(nullable = false, length = 500) private String shortDescription;
    @Column(nullable = false, length = 1000) private String thumbnailUrl;
    @Column(nullable = false) private long price;
    @Column(nullable = false, length = 3) private String currency;
    @Column(nullable = false) private Instant publishedAt;
    @Column(nullable = false, columnDefinition = "longtext") private String content;
    @Column(length = 1000) private String videoUrl;
    @Column(nullable = false) private long contentVersion;
    @Column(nullable = false, length = 100) private String buildVersion;
    @Column(nullable = false) private Instant updatedAt;
    @Column(nullable = false, length = 20) private String saleStatus;
    @Column(nullable = false) private long catalogVersion;

    @Column(nullable = false) private boolean listed;

    @BatchSize(size = 100)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "store_product_genre", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "genre_code", nullable = false, length = 32)
    private Set<String> genres = new HashSet<>();

    public List<String> getGenres() { return genres.stream().sorted().toList(); }

    public static StoreProduct create(CatalogProductSnapshot s) {
        StoreProduct product = new StoreProduct();
        product.productId = s.productId();
        product.productCode = s.productCode();
        product.publishedAt = s.publishedAt();
        product.replace(s);
        return product;
    }

    public void apply(CatalogProductSnapshot s) {
        if (!Objects.equals(productId, s.productId()) || !Objects.equals(productCode, s.productCode())
                || !Objects.equals(publishedAt, s.publishedAt()))
            throw new BusinessException(ErrorCode.CONFLICT, "공개 상품의 식별 정보가 다릅니다.");
        if (s.catalogVersion() < catalogVersion) return;
        if (s.catalogVersion() == catalogVersion) {
            if (!snapshot().equals(s))
                throw new BusinessException(ErrorCode.CONFLICT, "동일 버전의 공개 데이터가 다릅니다.");
            return;
        }
        if (s.contentVersion() < contentVersion)
            throw new BusinessException(ErrorCode.CONFLICT, "새 Catalog 버전의 콘텐츠 버전이 이전 공개본보다 낮습니다.");
        if (s.contentVersion() == contentVersion && !sameContent(s))
            throw new BusinessException(ErrorCode.CONFLICT, "동일 콘텐츠 버전의 공개 자료가 다릅니다.");
        replace(s);
    }

    public ReconciliationResult reconcile(CanonicalProduct canonical) {
        CatalogProductSnapshot source = canonical.snapshot();
        if (!Objects.equals(productId, source.productId()) || !Objects.equals(productCode, source.productCode())
                || !Objects.equals(publishedAt, source.publishedAt()))
            throw new BusinessException(ErrorCode.CONFLICT, "Catalog 정본의 상품 식별 정보가 다릅니다.");
        if (source.catalogVersion() < catalogVersion) return ReconciliationResult.STALE;
        if (source.catalogVersion() == catalogVersion) {
            if (snapshot().equals(source)) return ReconciliationResult.UNCHANGED;
            replace(source);
            return ReconciliationResult.REPAIRED;
        }
        apply(source);
        return ReconciliationResult.UPDATED;
    }

    private boolean sameContent(CatalogProductSnapshot s) {
        return Objects.equals(title, s.title()) && Objects.equals(shortDescription, s.shortDescription())
                && Objects.equals(thumbnailUrl, s.thumbnailUrl()) && price == s.price()
                && Objects.equals(currency, s.currency()) && Objects.equals(content, s.content())
                && Objects.equals(videoUrl, s.videoUrl()) && Objects.equals(buildVersion, s.buildVersion()) && getGenres().equals(s.genres());
    }

    private void replace(CatalogProductSnapshot s) {
        StoreProductValues.requireSnapshot(s);
        genres.clear();
        genres.addAll(s.genres());
        title = s.title(); shortDescription = s.shortDescription(); thumbnailUrl = s.thumbnailUrl();
        price = s.price(); currency = s.currency(); content = s.content(); videoUrl = s.videoUrl();
        contentVersion = s.contentVersion(); buildVersion = s.buildVersion(); updatedAt = s.updatedAt();
        saleStatus = s.saleStatus(); catalogVersion = s.catalogVersion(); listed = s.listed();
    }

    public CatalogProductSnapshot snapshot() {
        return new CatalogProductSnapshot(
                productId,
                productCode,
                title,
                shortDescription,
                thumbnailUrl,
                price,
                currency,
                publishedAt,
                content,
                videoUrl,
                contentVersion,
                buildVersion,
                updatedAt,
                saleStatus,
                catalogVersion,
                listed,
                getGenres()
        );
    }
}
