package com.stove.store.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stove.store.core.domain.CatalogUnavailableException;
import com.stove.store.core.port.CatalogProducts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SynchronizeCatalogProductsTest {
    @Test void failedInitializationRetriesAutomaticallyAtTheDueTimeAndSuccessUsesTheNormalInterval() {
        CatalogProducts catalog = mock(CatalogProducts.class);
        ReconcileCatalogProduct reconciliation = mock(ReconcileCatalogProduct.class);
        Clock clock = mock(Clock.class);
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-03T00:00:00Z"));
        when(clock.instant()).thenAnswer(call -> now.get());
        when(catalog.codes(0)).thenThrow(new CatalogUnavailableException());
        SynchronizeCatalogProducts sync = new SynchronizeCatalogProducts(catalog, reconciliation, clock, Duration.ofSeconds(5), Duration.ofMinutes(1));
        sync.poll();
        assertThat(sync.status().ready()).isFalse();
        assertThat(sync.failedRuns()).isEqualTo(1);
        sync.poll();
        verify(catalog, times(1)).codes(0);
        now.set(now.get().plusSeconds(5));
        doReturn(List.of()).when(catalog).codes(0);
        sync.poll();
        assertThat(sync.status().ready()).isTrue();
        now.set(now.get().plusSeconds(59));
        sync.poll();
        verify(catalog, times(2)).codes(0);
        now.set(now.get().plusSeconds(1));
        sync.poll();
        verify(catalog, times(3)).codes(0);
        assertThat(sync.successfulRuns()).isEqualTo(2);
    }

    @Test void overlappingPollsDoNotRunTheSameLocalScanTwice() throws Exception {
        CatalogProducts catalog = mock(CatalogProducts.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(catalog.codes(0)).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return List.of();
        });
        SynchronizeCatalogProducts sync = new SynchronizeCatalogProducts(catalog, mock(ReconcileCatalogProduct.class), Clock.systemUTC(), Duration.ofSeconds(5), Duration.ofMinutes(1));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = executor.submit(sync::poll);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            sync.poll();
            verify(catalog, times(1)).codes(0);
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertThat(sync.successfulRuns()).isEqualTo(1);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void sourceOutageStopsThePassInsteadOfTimingOutForEveryRemainingProduct() {
        CatalogProducts catalog = mock(CatalogProducts.class);
        ReconcileCatalogProduct reconciliation = mock(ReconcileCatalogProduct.class);
        when(catalog.codes(0)).thenReturn(List.of("game-1", "game-2"));
        when(reconciliation.execute("game-1")).thenThrow(new CatalogUnavailableException());
        SynchronizeCatalogProducts sync = new SynchronizeCatalogProducts(catalog, reconciliation, Clock.systemUTC(), Duration.ofSeconds(5), Duration.ofMinutes(1));
        sync.poll();
        verify(reconciliation, never()).execute("game-2");
        assertThat(sync.status().failed()).isEqualTo(1);
        assertThat(sync.status().ready()).isFalse();
    }
}
