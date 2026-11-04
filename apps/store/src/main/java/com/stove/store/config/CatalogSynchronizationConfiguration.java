package com.stove.store.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.store.adapter.StoreSynchronizationMetrics;
import com.stove.store.api.scheduler.CatalogSynchronizationScheduler;
import com.stove.store.core.domain.StoreProductRepository;
import com.stove.store.core.domain.StoreReconciliationRepository;
import com.stove.store.core.port.CatalogProducts;
import com.stove.store.core.service.ReconcileCatalogProduct;
import com.stove.store.core.service.SynchronizeCatalogProducts;
import io.micrometer.core.instrument.binder.MeterBinder;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class CatalogSynchronizationConfiguration {
    @Bean Clock storeSynchronizationClock() { return Clock.systemUTC(); }

    @Bean ReconcileCatalogProduct reconcileCatalogProduct(CatalogProducts catalog, StoreProductRepository products,
            StoreReconciliationRepository reconciliations, EntityManager entities, ObjectMapper mapper,
            Clock clock, PlatformTransactionManager transactions) {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return new ReconcileCatalogProduct(catalog, products, reconciliations, entities, mapper, clock, transaction);
    }

    @Bean SynchronizeCatalogProducts synchronizeCatalogProducts(CatalogProducts catalog, ReconcileCatalogProduct reconciliation,
            Clock clock, @Value("${stove.store.sync.retry-delay:PT5S}") Duration retryDelay,
            @Value("${stove.store.sync.reconcile-interval:PT1M}") Duration reconcileInterval) {
        return new SynchronizeCatalogProducts(catalog, reconciliation, clock, retryDelay, reconcileInterval);
    }

    @Bean
    @ConditionalOnProperty(prefix = "stove.store.sync", name = "enabled", havingValue = "true", matchIfMissing = true)
    SchedulingConfigurer catalogSynchronizationScheduler(SynchronizeCatalogProducts synchronization,
            @Value("${stove.store.sync.poll-interval-ms:1000}") long intervalMillis) {
        if (intervalMillis <= 0) throw new IllegalArgumentException("Store synchronization poll interval must be positive");
        CatalogSynchronizationScheduler scheduler = new CatalogSynchronizationScheduler(synchronization);
        return (ScheduledTaskRegistrar registrar) -> registrar.addFixedDelayTask(scheduler::poll, Duration.ofMillis(intervalMillis));
    }

    @Bean MeterBinder storeSynchronizationMetrics(ObjectProvider<SynchronizeCatalogProducts> synchronization) {
        return new StoreSynchronizationMetrics(() -> synchronization.getObject().status(),
                () -> synchronization.getObject().successfulRuns(), () -> synchronization.getObject().failedRuns());
    }
}
