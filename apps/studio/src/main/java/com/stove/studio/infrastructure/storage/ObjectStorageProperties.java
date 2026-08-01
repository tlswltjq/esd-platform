package com.stove.studio.infrastructure.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 오브젝트 스토리지 접속 설정. 어댑터만 쓰므로 어댑터 옆에 둔다.
 *
 * <p>로컬은 MinIO, 운영은 S3 를 가정한다. 둘 다 S3 API 를 쓰므로 {@code endpoint} 만 다르다.
 *
 * @param endpoint    S3 호환 엔드포인트. 비우면 AWS 기본 엔드포인트를 쓴다.
 * @param presignTtl  발급한 presigned URL 의 유효 시간
 */
@ConfigurationProperties(prefix = "stove.storage")
public record ObjectStorageProperties(
        String endpoint,
        String region,
        String accessKey,
        String secretKey,
        String bucket,
        Duration presignTtl
) {
    public ObjectStorageProperties {
        region = region == null ? "us-east-1" : region;
        bucket = bucket == null ? "stove-builds" : bucket;
        presignTtl = presignTtl == null ? Duration.ofMinutes(15) : presignTtl;
    }

    /** MinIO 처럼 가상 호스트 스타일을 지원하지 않는 엔드포인트를 쓸 때 경로 스타일이 필요하다. */
    public boolean pathStyleRequired() {
        return endpoint != null && !endpoint.isBlank();
    }
}
