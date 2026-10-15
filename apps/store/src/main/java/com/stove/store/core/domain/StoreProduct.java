package com.stove.store.core.domain;

import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.common.web.error.*;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.Objects;

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

    public static StoreProduct create(CatalogProductSnapshot s) {
        var product = new StoreProduct();
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
        if (s.contentVersion() < contentVersion) return;
        if (s.contentVersion() == contentVersion) {
            if (!snapshot().equals(s))
                throw new BusinessException(ErrorCode.CONFLICT, "동일 버전의 공개 데이터가 다릅니다.");
            return;
        }
        replace(s);
    }

    private void replace(CatalogProductSnapshot s) {
        title = s.title(); shortDescription = s.shortDescription(); thumbnailUrl = s.thumbnailUrl();
        price = s.price(); currency = s.currency(); content = s.content(); videoUrl = s.videoUrl();
        contentVersion = s.contentVersion(); buildVersion = s.buildVersion(); updatedAt = s.updatedAt();
    }

    public CatalogProductSnapshot snapshot() {
        return new CatalogProductSnapshot(productId, productCode, title, shortDescription,
                thumbnailUrl, price, currency, publishedAt, content, videoUrl,
                contentVersion, buildVersion, updatedAt);
    }
}
