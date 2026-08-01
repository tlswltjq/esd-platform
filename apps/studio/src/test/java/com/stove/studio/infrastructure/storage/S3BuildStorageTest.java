package com.stove.studio.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.studio.core.domain.UploadTicket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * presigned PUT URL 이 <b>실제로 업로드되는지</b>까지 확인한다.
 * URL 문자열 모양만 맞는 것으로는 어댑터가 동작한다고 말할 수 없다.
 */
@Testcontainers
class S3BuildStorageTest {

    private static final String USER = "stove";
    private static final String PASSWORD = "stove1234";
    private static final String BUCKET = "stove-builds";

    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:latest")
            .withUserName(USER)
            .withPassword(PASSWORD);

    private S3BuildStorage storage;

    @BeforeEach
    void setUp() {
        storage = new S3BuildStorage(new ObjectStorageProperties(
                MINIO.getS3URL(), "us-east-1", USER, PASSWORD, BUCKET, Duration.ofMinutes(5)));
        storage.createBucketIfAbsent();
    }

    @Test
    @DisplayName("발급한 presigned URL 로 빌드가 실제 업로드된다")
    void uploadsThroughPresignedUrl() throws Exception {
        byte[] build = "fake game binary".getBytes(StandardCharsets.UTF_8);

        UploadTicket ticket = storage.issueUploadTicket("GAME-TEST-001", "1.0.0");

        assertThat(ticket.storagePath()).isEqualTo("s3://stove-builds/GAME-TEST-001/1.0.0/game.pak");

        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(ticket.uploadUrl()))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(build))
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(readObject("GAME-TEST-001/1.0.0/game.pak")).isEqualTo(build);
    }

    @Test
    @DisplayName("버킷 생성은 여러 번 호출해도 안전하다")
    void bucketCreationIsIdempotent() {
        storage.createBucketIfAbsent();
        storage.createBucketIfAbsent();

        UploadTicket ticket = storage.issueUploadTicket("GAME-TEST-002", "2.0.0");
        assertThat(ticket.uploadUrl()).contains("GAME-TEST-002/2.0.0/game.pak");
    }

    private byte[] readObject(String key) {
        try (S3Client client = S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(USER, PASSWORD)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            return client.getObject(
                    GetObjectRequest.builder().bucket(BUCKET).key(key).build(),
                    ResponseTransformer.toBytes()).asByteArray();
        }
    }
}
