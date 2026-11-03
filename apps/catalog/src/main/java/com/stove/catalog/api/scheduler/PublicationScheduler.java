package com.stove.catalog.api.scheduler;

import com.stove.catalog.core.service.PublishQueuedReleases;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class PublicationScheduler {
    private final PublishQueuedReleases publish;

    public void poll() { publish.execute(); }
}
