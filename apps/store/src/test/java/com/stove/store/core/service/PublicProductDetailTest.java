package com.stove.store.core.service;

import static com.stove.store.StoreFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.common.web.error.BusinessException;
import com.stove.store.core.domain.StoreProduct;
import com.stove.store.core.domain.StoreProductRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PublicProductDetailTest {
    @Test void hiddenProductsAreNotPublicDetails() {
        CatalogProductSnapshot p = product(1, 1, "Title", 1000);
        CatalogProductSnapshot hidden = new CatalogProductSnapshot(p.productId(), p.productCode(), p.title(), p.shortDescription(),
                p.thumbnailUrl(), p.price(), p.currency(), p.publishedAt(), p.content(), p.videoUrl(), p.contentVersion(), p.buildVersion(),
                p.updatedAt(), p.saleStatus(), p.catalogVersion(), false);
        StoreProductRepository products = mock(StoreProductRepository.class);
        when(products.findByProductCode(p.productCode())).thenReturn(Optional.of(StoreProduct.create(hidden)));
        ReadStoreProduct read = new ReadStoreProduct(products, mock(SynchronizeCatalogProducts.class));
        assertThatThrownBy(() -> read.execute(p.productCode())).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().statusCode()).isEqualTo(404));
    }
}
