package com.stove.store.core.domain;

import static com.stove.store.StoreFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.common.web.error.BusinessException;
import org.junit.jupiter.api.Test;

class StoreSaleStatusTest {
    @Test void historicalPublicationJsonUsesItsContentVersionAndCannotOverrideANewerSaleChange() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CatalogProductSnapshot original = product(1, 4, "Historical", 1000);
        ObjectNode historical = mapper.valueToTree(original);
        historical.remove("saleStatus");
        historical.remove("catalogVersion");
        CatalogProductSnapshot old = mapper.treeToValue(historical, CatalogProductSnapshot.class);
        assertThat(old).isEqualTo(original);
        CatalogProductSnapshot stopped = sales(original, "STOPPED", 5);
        StoreProduct stored = StoreProduct.create(stopped);
        stored.apply(old);
        assertThat(stored.snapshot()).isEqualTo(stopped);
    }
    @Test void salesChangesAtTheSameContentVersionSurviveOldPublicationAndHttpSnapshots() {
        CatalogProductSnapshot original = product(1, 1, "Title", 1000);
        StoreProduct stored = StoreProduct.create(original);
        CatalogProductSnapshot stopped = sales(original, "STOPPED", 2);
        stored.apply(stopped);
        stored.apply(original);
        stored.apply(stopped);
        assertThat(stored.snapshot()).isEqualTo(stopped);
        CatalogProductSnapshot resumed = sales(original, "ON_SALE", 3);
        stored.apply(resumed);
        stored.apply(stopped);
        assertThat(stored.snapshot()).isEqualTo(resumed);
    }

    @Test void conflictingSalesAtTheSameCatalogVersionDoNotOverwriteTheProjection() {
        CatalogProductSnapshot stopped = sales(product(1, 1, "Title", 1000), "STOPPED", 2);
        StoreProduct stored = StoreProduct.create(stopped);
        assertThatThrownBy(() -> stored.apply(sales(stopped, "ON_SALE", 2)))
                .isInstanceOf(BusinessException.class);
        assertThat(stored.snapshot()).isEqualTo(stopped);
    }

    private CatalogProductSnapshot sales(CatalogProductSnapshot p, String status, long version) {
        return new CatalogProductSnapshot(
                p.productId(),
                p.productCode(),
                p.title(),
                p.shortDescription(),
                p.thumbnailUrl(),
                p.price(),
                p.currency(),
                p.publishedAt(),
                p.content(),
                p.videoUrl(),
                p.contentVersion(),
                p.buildVersion(),
                p.updatedAt(),
                status,
                version,
                "ON_SALE".equals(status),
                p.genres()
        );
    }
}
