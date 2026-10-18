package com.stove.review.config;

import com.stove.review.adapter.storage.S3ReviewBuildStorage;
import org.springframework.context.annotation.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.presigner.*;
import java.net.URI;

@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {
    @Bean
    public S3ReviewBuildStorage buildStorage(ReviewStorageProperties properties) {
        StaticCredentialsProvider credentials =
                StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
        S3Configuration config =
                S3Configuration.builder().pathStyleAccessEnabled(properties.pathStyleRequired()).build();
        S3ClientBuilder client =
                S3Client.builder()
                        .region(Region.of(properties.region()))
                        .credentialsProvider(credentials)
                        .serviceConfiguration(config);
        S3Presigner.Builder signer =
                S3Presigner.builder()
                        .region(Region.of(properties.region()))
                        .credentialsProvider(credentials)
                        .serviceConfiguration(config);
        if (properties.pathStyleRequired()) {
            client.endpointOverride(URI.create(properties.endpoint()));
            signer.endpointOverride(
                    URI.create(
                            properties.presignEndpoint() == null || properties.presignEndpoint().isBlank()
                                    ? properties.endpoint()
                                    : properties.presignEndpoint()));
        }
        return new S3ReviewBuildStorage(client.build(), signer.build(), properties.bucket(), properties.presignTtl());
    }
}
