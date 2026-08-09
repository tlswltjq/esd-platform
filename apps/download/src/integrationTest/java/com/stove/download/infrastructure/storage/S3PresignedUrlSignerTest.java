package com.stove.download.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.download.core.domain.SignedUrl;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * 서명한 URL 로 <b>실제 다운로드가 되는지</b> 확인한다.
 * studio 가 올려둔 자리에서 download 가 꺼내오는 경로를 그대로 재현한다.
 */
@Testcontainers
class S3PresignedUrlSignerTest {

    private static final String USER = "stove";
    private static final String PASSWORD = "stove1234";
    private static final String BUCKET = "stove-builds";
    private static final String KEY = "GAME-TEST-001/1.0.0/game.pak";
    private static final byte[] BUILD = "fake game binary".getBytes(StandardCharsets.UTF_8);

    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:latest")
            .withUserName(USER)
            .withPassword(PASSWORD);

    @BeforeAll
    static void putBuild() {
        try (S3Client client = S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(USER, PASSWORD)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
            client.putObject(PutObjectRequest.builder().bucket(BUCKET).key(KEY).build(),
                    RequestBody.fromBytes(BUILD));
        }
    }

    private S3PresignedUrlSigner signer(Duration ttl) {
        return new S3PresignedUrlSigner(new ObjectStorageProperties(
                MINIO.getS3URL(), "us-east-1", USER, PASSWORD, ttl));
    }

    @Test
    @DisplayName("서명한 URL 로 빌드를 실제로 내려받는다")
    void downloadsThroughSignedUrl() throws Exception {
        SignedUrl signed = signer(Duration.ofMinutes(10)).sign("s3://%s/%s".formatted(BUCKET, KEY), 42L);

        assertThat(signed.expiresAt()).isAfter(Instant.now());

        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(signed.url())).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(BUILD);
    }

    @Test
    @DisplayName("서명 없이 같은 주소를 치면 거부된다")
    void rejectsUnsignedAccess() throws Exception {
        String unsigned = "%s/%s/%s".formatted(MINIO.getS3URL(), BUCKET, KEY);

        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(unsigned)).GET().build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("저장 경로 형식이 어긋나면 발급하지 않는다")
    void rejectsMalformedStoragePath() {
        assertThatThrownBy(() -> signer(Duration.ofMinutes(10)).sign("s3://bucket-only", 42L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
