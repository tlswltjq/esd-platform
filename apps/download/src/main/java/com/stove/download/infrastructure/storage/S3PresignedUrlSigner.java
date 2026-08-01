package com.stove.download.infrastructure.storage;

import com.stove.download.core.domain.SignedUrl;
import com.stove.download.core.port.DownloadUrlSigner;
import java.net.URI;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * {@link DownloadUrlSigner} 의 두 번째 어댑터 — 오브젝트 스토리지 presigned GET.
 *
 * <p>{@code CdnUrlSigner} 와 같은 포트를 구현하지만 전략이 다르다.
 * <ul>
 *   <li>CDN 전략 — 자체 HMAC 토큰. 엣지에서 인증을 끝내 원본 부하를 줄인다.</li>
 *   <li>이 전략 — 스토리지가 직접 서명. CDN 없이도 실제 파일이 내려간다.</li>
 * </ul>
 * 실제 운영은 보통 S3 저장 + CDN 배포이므로 두 축은 독립적으로 고른다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "stove.download.url-strategy", havingValue = "s3")
public class S3PresignedUrlSigner implements DownloadUrlSigner {

    private final ObjectStorageProperties properties;
    private final S3Presigner presigner;

    public S3PresignedUrlSigner(ObjectStorageProperties properties) {
        this.properties = properties;
        var builder = S3Presigner.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyleRequired())
                        .build());
        if (properties.pathStyleRequired()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        this.presigner = builder.build();
    }

    /**
     * @param storagePath studio 가 발급한 {@code s3://버킷/키} 형식 경로
     * @param memberId    서명에는 쓰이지 않는다 — 소유 판정은 이미 끝났고, 이 URL 은 짧게 만료된다
     */
    @Override
    public SignedUrl sign(String storagePath, Long memberId) {
        String withoutScheme = storagePath.replaceFirst("^s3://", "");
        int slash = withoutScheme.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("저장 경로 형식이 올바르지 않습니다: " + storagePath);
        }
        String bucket = withoutScheme.substring(0, slash);
        String key = withoutScheme.substring(slash + 1);

        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(properties.presignTtl())
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .build();

        String url = presigner.presignGetObject(request).url().toString();
        Instant expiresAt = Instant.now().plus(properties.presignTtl());

        log.info("다운로드 서명 URL 발급 memberId={} key={} (ttl={})", memberId, key, properties.presignTtl());
        return new SignedUrl(url, expiresAt);
    }
}
