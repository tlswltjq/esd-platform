package com.stove.studio.config;

import com.stove.studio.core.domain.build.BuildRepository;
import com.stove.studio.core.domain.project.ProjectRepository;
import com.stove.studio.core.port.BuildStorage;
import com.stove.studio.core.service.build.RegisterBuild;
import com.stove.studio.core.service.project.*;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class StudioUseCaseConfiguration {
    @Bean
    RegisterBuild registerBuild(ReadProjects read, ProjectRepository projects, BuildRepository builds,
            BuildStorage storage, PlatformTransactionManager transactions, StudioUploadProperties properties) {
        return new RegisterBuild(read, projects, builds, storage, transactions, properties.maxArtifactBytes());
    }

    @Bean
    UploadThumbnail uploadThumbnail(ReadProjects read, ProjectRepository projects, BuildStorage storage,
            PlatformTransactionManager transactions, StudioAssetProperties properties) {
        return new UploadThumbnail(read, projects, storage, transactions,
                properties.publicBaseUrl(), properties.maxThumbnailBytes());
    }

    @Bean
    UpdateStorePage updateStorePage(ProjectRepository projects, BuildStorage storage, StudioAssetProperties properties) {
        return new UpdateStorePage(projects, storage, properties.publicBaseUrl());
    }
}
