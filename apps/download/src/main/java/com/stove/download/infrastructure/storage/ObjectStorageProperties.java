package com.stove.download.infrastructure.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 오브젝트 스토리지 접속 설정. studio 가 빌드를 올려둔 그 버킷을 읽는다.
 *
 * @param presignTtl 발급한 다운로드 URL 의 유효 시간. 짧게 유지해 링크 공유를 막는다.
 */
@ConfigurationProperties(prefix = "stove.storage")
public record ObjectStorageProperties(
        String endpoint,
        String region,
        String accessKey,
        String secretKey,
        Duration presignTtl
) {
    public ObjectStorageProperties {
        region = region == null ? "us-east-1" : region;
        presignTtl = presignTtl == null ? Duration.ofMinutes(10) : presignTtl;
    }

    public boolean pathStyleRequired() {
        return endpoint != null && !endpoint.isBlank();
    }
}
