package com.stove.download.config;

import com.stove.download.infrastructure.cdn.CdnUrlSigner;
import com.stove.download.infrastructure.storage.S3PresignedUrlSigner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import java.net.URI;

@Configuration(proxyBeanMethods = false)
public class DownloadUrlConfiguration {
    @Bean
    @ConditionalOnProperty(name = "stove.download.url-strategy", havingValue = "cdn", matchIfMissing = true)
    public CdnUrlSigner cdnUrlSigner(CdnProperties properties) {
        return new CdnUrlSigner(properties.baseUrl(), properties.signingKey(), properties.ttl());
    }

    @Bean
    @ConditionalOnProperty(name = "stove.download.url-strategy", havingValue = "s3")
    public S3PresignedUrlSigner s3UrlSigner(ObjectStorageProperties properties) {
        var builder = S3Presigner.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyleRequired())
                        .build());
        if (properties.pathStyleRequired()) {
            String publicEndpoint = properties.presignEndpoint() == null
                    || properties.presignEndpoint().isBlank()
                    ? properties.endpoint() : properties.presignEndpoint();
            builder.endpointOverride(URI.create(publicEndpoint));
        }
        return new S3PresignedUrlSigner(builder.build(), properties.presignTtl());
    }
}
