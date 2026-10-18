package com.stove.studio.config;

import com.stove.studio.adapter.storage.S3BuildStorage;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {
    @Bean
    @ConditionalOnProperty(name = "stove.storage.provider", havingValue = "s3", matchIfMissing = true)
    public S3BuildStorage buildStorage(ObjectStorageProperties properties) {
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
        return new S3BuildStorage(client.build(), signer.build(), properties.bucket(), properties.presignTtl(), properties.encryptionEnabled());
    }
}
