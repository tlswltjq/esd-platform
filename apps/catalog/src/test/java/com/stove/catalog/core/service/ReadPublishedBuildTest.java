package com.stove.catalog.core.service;

import static com.stove.catalog.CatalogFixtures.envelope;
import static com.stove.catalog.CatalogFixtures.event;
import static com.stove.catalog.CatalogFixtures.id;
import static com.stove.catalog.CatalogFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.catalog.api.listener.ReleaseSubmissionMapper;
import com.stove.catalog.core.domain.CatalogProduct;
import com.stove.catalog.core.domain.CatalogProductRepository;
import com.stove.catalog.core.domain.CatalogRelease;
import com.stove.catalog.core.domain.CatalogReleaseRepository;
import com.stove.catalog.core.domain.PublishedBuild;
import com.stove.common.kafka.contract.payload.ProjectReleaseRequestedEvent;
import com.stove.common.web.error.BusinessException;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ReadPublishedBuildTest {
    @Test void returnsTheInitiallySelectedImmutableReleaseWhenPublicationChangesDuringRead() {
        ProjectReleaseRequestedEvent event = event(id(), 1);
        CatalogProduct product = CatalogProduct.publish(event.snapshot(), event.releaseRequestId(), Instant.now());
        ReflectionTestUtils.setField(product, "id", 1L);
        CatalogRelease original = CatalogRelease.decided(ReleaseSubmissionMapper.from(envelope(event), event),
                json(event.snapshot()), false, Instant.now());
        CatalogProductRepository products = mock(CatalogProductRepository.class);
        CatalogReleaseRepository releases = mock(CatalogReleaseRepository.class);
        when(products.findByProductCode(event.partitionKey())).thenReturn(Optional.of(product));
        when(releases.findById(event.releaseRequestId())).thenAnswer(call -> {
            product.replace(event.releaseRequestId() + 1, Instant.now());
            return Optional.of(original);
        });
        ReadPublishedBuild read = new ReadPublishedBuild(products, releases, new ObjectMapper().findAndRegisterModules());
        PublishedBuild result = read.execute(event.partitionKey());
        assertThat(result.releaseRequestId()).isEqualTo(event.releaseRequestId());
        assertThat(result.contentVersion()).isEqualTo(1);
        assertThat(product.getCurrentReleaseRequestId()).isEqualTo(event.releaseRequestId() + 1);
        verify(releases).findById(event.releaseRequestId());
        verifyNoMoreInteractions(releases);
    }

    @Test void rejectsDatabaseCaseFoldingThatWouldChangeTheProductIdentity() {
        ProjectReleaseRequestedEvent event = event(id(), 1);
        CatalogProduct product = CatalogProduct.publish(event.snapshot(), event.releaseRequestId(), Instant.now());
        CatalogProductRepository products = mock(CatalogProductRepository.class);
        CatalogReleaseRepository releases = mock(CatalogReleaseRepository.class);
        String upper = event.partitionKey().toUpperCase(Locale.ROOT);
        when(products.findByProductCode(upper)).thenReturn(Optional.of(product));
        ReadPublishedBuild read = new ReadPublishedBuild(products, releases, new ObjectMapper());
        assertThatThrownBy(() -> read.execute(upper)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(releases);
    }
}
