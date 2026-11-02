package com.stove.catalog.config;

import com.stove.catalog.adapter.storage.S3BuildDistribution;
import com.stove.catalog.adapter.storage.UnavailableBuildDistribution;
import com.stove.catalog.api.scheduler.DownloadStorageScheduler;
import com.stove.catalog.core.port.BuildDistribution;
import com.stove.catalog.core.service.ReconcileDownloadStorage;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DownloadStorageProperties.class)
public class DownloadStorageConfiguration {
    @Bean
    BuildDistribution buildDistribution(DownloadStorageProperties properties) {
        if (!properties.enabled()) return new UnavailableBuildDistribution();
        return new S3BuildDistribution(client(properties, properties.accessKey(), properties.secretKey()),
                client(properties, properties.readerAccessKey(), properties.readerSecretKey()), properties.bucket());
    }
    @Bean
    @ConditionalOnProperty(name = "stove.catalog.download-storage.worker-enabled", havingValue = "true", matchIfMissing = true)
    DownloadStorageScheduler downloadStorageScheduler(ReconcileDownloadStorage reconcile) {
        return new DownloadStorageScheduler(reconcile);
    }
    private S3Client client(DownloadStorageProperties properties, String key, String secret) {
        boolean custom = properties.endpoint() != null && !properties.endpoint().isBlank();
        S3ClientBuilder builder = S3Client.builder().region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(custom).build())
                .overrideConfiguration(c -> c.apiCallTimeout(properties.callTimeout()).apiCallAttemptTimeout(properties.callTimeout()));
        if (custom) builder.endpointOverride(URI.create(properties.endpoint()));
        return builder.build();
    }
}
