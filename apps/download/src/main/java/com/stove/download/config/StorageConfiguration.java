package com.stove.download.config;

import com.stove.download.adapter.storage.S3BuildStorage;
import java.net.URI;
import org.springframework.context.annotation.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {
    @Bean
    public S3BuildStorage buildStorage(ObjectStorageProperties properties) {
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
        boolean custom = properties.endpoint() != null && !properties.endpoint().isBlank();
        S3Configuration config = S3Configuration.builder().pathStyleAccessEnabled(custom).build();
        S3ClientBuilder client = S3Client.builder().region(Region.of(properties.region()))
                .credentialsProvider(credentials).serviceConfiguration(config)
                .overrideConfiguration(c -> c.apiCallTimeout(properties.callTimeout())
                        .apiCallAttemptTimeout(properties.callTimeout()));
        S3Presigner.Builder signer = S3Presigner.builder().region(Region.of(properties.region()))
                .credentialsProvider(credentials).serviceConfiguration(config);
        if (custom) client.endpointOverride(URI.create(properties.endpoint()));
        String external = properties.presignEndpoint();
        if (external != null && !external.isBlank()) signer.endpointOverride(URI.create(external));
        else if (custom) signer.endpointOverride(URI.create(properties.endpoint()));
        return new S3BuildStorage(client.build(), signer.build(), properties.bucket(), properties.presignTtl());
    }
}
