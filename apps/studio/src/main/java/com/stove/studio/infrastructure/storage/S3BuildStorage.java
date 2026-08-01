package com.stove.studio.infrastructure.storage;

import com.stove.studio.core.domain.UploadTicket;
import com.stove.studio.core.port.BuildStorage;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * {@link BuildStorage} 의 S3 호환 어댑터(로컬 MinIO / 운영 S3).
 *
 * <p>서버는 바이너리를 직접 받지 않는다. presigned PUT URL 만 발급하고
 * 실제 업로드는 클라이언트가 스토리지로 바로 보낸다 — 수 GB 짜리 게임 빌드가
 * 애플리케이션 서버를 통과하지 않게 하는 것이 핵심이다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "stove.storage.provider", havingValue = "s3")
public class S3BuildStorage implements BuildStorage {

    private final ObjectStorageProperties properties;
    private final S3Client s3;
    private final S3Presigner presigner;

    public S3BuildStorage(ObjectStorageProperties properties) {
        this.properties = properties;
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
        S3Configuration serviceConfig = S3Configuration.builder()
                .pathStyleAccessEnabled(properties.pathStyleRequired())
                .build();

        var clientBuilder = S3Client.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(serviceConfig);
        var presignerBuilder = S3Presigner.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(serviceConfig);

        if (properties.pathStyleRequired()) {
            URI endpoint = URI.create(properties.endpoint());
            clientBuilder.endpointOverride(endpoint);
            presignerBuilder.endpointOverride(endpoint);
        }
        this.s3 = clientBuilder.build();
        this.presigner = presignerBuilder.build();
    }

    /** 버킷이 없으면 만든다. compose 사이드카 대신 여기서 해야 테스트·CI 에서도 같게 동작한다. */
    @PostConstruct
    void createBucketIfAbsent() {
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(properties.bucket()).build());
            log.info("빌드 버킷 생성 {}", properties.bucket());
        } catch (S3Exception e) {
            // BucketAlreadyOwnedByYou / BucketAlreadyExists — 정상 경로다
            log.debug("빌드 버킷 이미 존재 {}", properties.bucket());
        }
    }

    @Override
    public UploadTicket issueUploadTicket(String productCode, String version) {
        String key = "%s/%s/game.pak".formatted(productCode, version);

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(properties.presignTtl())
                .putObjectRequest(PutObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(key)
                        .build())
                .build();

        String uploadUrl = presigner.presignPutObject(presignRequest).url().toString();
        String storagePath = "s3://%s/%s".formatted(properties.bucket(), key);

        log.info("빌드 업로드 경로 발급 {} (ttl={})", storagePath, properties.presignTtl());
        return new UploadTicket(storagePath, uploadUrl);
    }
}
