package com.stove.catalog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.catalog.api.scheduler.PublicationScheduler;
import com.stove.catalog.adapter.trace.DisabledPublicationTracing;
import com.stove.catalog.adapter.trace.MicrometerPublicationTracing;
import com.stove.catalog.core.domain.CatalogPublicationJobRepository;
import com.stove.catalog.core.service.PublishProjectRelease;
import com.stove.catalog.core.service.PublishQueuedReleases;
import com.stove.catalog.core.port.PublicationTracing;
import com.stove.common.messaging.trace.TraceContextCapture;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class PublicationConfiguration {
    @Bean
    PublishQueuedReleases publishQueuedReleases(CatalogPublicationJobRepository jobs, PublishProjectRelease publish,
            ObjectMapper mapper, PlatformTransactionManager manager,
            PublicationTracing tracing,
            @Value("${stove.catalog.publication.batch-size:20}") int batchSize) {
        if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("Publication batch size must be 1..100");
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return new PublishQueuedReleases(jobs, publish, mapper, transaction, batchSize, tracing);
    }

    @Bean
    PublicationTracing publicationTracing(ObjectProvider<Tracer> tracers, ObjectProvider<Propagator> propagators,
            TraceContextCapture capture) {
        Tracer tracer = tracers.getIfAvailable();
        Propagator propagator = propagators.getIfAvailable();
        if (tracer == null || propagator == null) return new DisabledPublicationTracing();
        return new MicrometerPublicationTracing(tracer, propagator, capture);
    }

    @Bean
    @ConditionalOnProperty(name = "stove.catalog.publication.worker-enabled", havingValue = "true", matchIfMissing = true)
    SchedulingConfigurer publicationScheduler(PublishQueuedReleases publish,
            @Value("${stove.catalog.publication.poll-interval-ms:200}") long intervalMillis) {
        if (intervalMillis <= 0) throw new IllegalArgumentException("Publication poll interval must be positive");
        PublicationScheduler scheduler = new PublicationScheduler(publish);
        return (ScheduledTaskRegistrar registrar) ->
                registrar.addFixedDelayTask(scheduler::poll, Duration.ofMillis(intervalMillis));
    }
}
