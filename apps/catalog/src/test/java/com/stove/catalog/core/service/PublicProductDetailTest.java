package com.stove.catalog.core.service;

import static com.stove.catalog.CatalogFixtures.event;
import static com.stove.catalog.CatalogFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.catalog.core.domain.CatalogProduct;
import com.stove.catalog.core.domain.CatalogProductRepository;
import com.stove.catalog.core.domain.CatalogReleaseRepository;
import com.stove.catalog.core.domain.ListingPreference;
import com.stove.common.kafka.contract.payload.ProjectReleaseRequestedEvent;
import com.stove.common.web.error.BusinessException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PublicProductDetailTest {
    @Test void hiddenProductsAreNotPublicDetailsAndTheirReleaseIsNotRead() {
        ProjectReleaseRequestedEvent event = event(id(), 1);
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        CatalogProduct product = CatalogProduct.publish(event.snapshot(), event.releaseRequestId(), now);
        product.changeListingPreference(ListingPreference.UNLISTED, 1, now);
        CatalogProductRepository products = mock(CatalogProductRepository.class);
        CatalogReleaseRepository releases = mock(CatalogReleaseRepository.class);
        when(products.findByProductCode(event.partitionKey())).thenReturn(Optional.of(product));
        ReadPublishedProducts read = new ReadPublishedProducts(products, releases, new ObjectMapper());
        assertThatThrownBy(() -> read.detail(event.partitionKey())).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().statusCode()).isEqualTo(404));
        verifyNoInteractions(releases);
    }
}
