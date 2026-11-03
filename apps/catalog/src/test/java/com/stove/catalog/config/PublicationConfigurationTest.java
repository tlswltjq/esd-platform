package com.stove.catalog.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.stove.catalog.core.service.PublishQueuedReleases;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

class PublicationConfigurationTest {
    @Test
    void registersTheConfiguredIntervalAndInvokesThePublicationWorker() {
        PublishQueuedReleases publish = mock(PublishQueuedReleases.class);
        PublicationConfiguration configuration = new PublicationConfiguration();
        SchedulingConfigurer scheduling = configuration.publicationScheduler(publish, 200);
        ScheduledTaskRegistrar registrar = mock(ScheduledTaskRegistrar.class);
        scheduling.configureTasks(registrar);
        ArgumentCaptor<Runnable> work = ArgumentCaptor.forClass(Runnable.class);
        verify(registrar).addFixedDelayTask(work.capture(), eq(Duration.ofMillis(200)));
        work.getValue().run();
        verify(publish).execute();
        assertThatThrownBy(() -> configuration.publicationScheduler(publish, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
