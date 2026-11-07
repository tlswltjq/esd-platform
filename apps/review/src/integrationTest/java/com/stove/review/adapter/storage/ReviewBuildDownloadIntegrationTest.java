package com.stove.review.adapter.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.common.web.error.BusinessException;
import com.stove.review.ReviewFixtures;
import com.stove.review.ReviewIntegrationTestBase;
import com.stove.review.config.ReviewStorageProperties;
import com.stove.review.config.StorageConfiguration;
import com.stove.review.core.domain.BuildDownload;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;

class ReviewBuildDownloadIntegrationTest extends ReviewIntegrationTestBase {
    @Autowired S3ReviewBuildStorage storage;

    @Test
    @DisplayName("[D-041] 첫 요청이 지연돼도 제출 빌드의 바이트와 체크섬을 검증한다")
    void downloadedBytesMatchTheSubmittedBuildAndUrlExpires() throws Exception {
        byte[] bytes = "Immutable uploaded ZIP".getBytes(StandardCharsets.UTF_8);
        String checksum =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        ProjectSnapshot snapshot = ReviewFixtures.snapshot(1, object(bytes), bytes.length, checksum);
        S3ReviewBuildStorage shortLived =
                new StorageConfiguration().buildStorage(
                        new ReviewStorageProperties(
                                MINIO.getS3URL(),
                                MINIO.getS3URL(),
                                "us-east-1",
                                "stove",
                                "stove1234",
                                "stove-builds",
                                Duration.ofSeconds(1)));
        try {
            BuildDownload url = storage.download(snapshot);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.url())).GET().build();
            HttpClient client = HttpClient.newHttpClient();
            // 기대: 느린 러너에서도 내용 검증은 200이다.
            // 짧은 URL의 수명보다 지연돼도 내용 검증용 URL은 유효해야 한다.
            Thread.sleep(2100);
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(bytes);
            assertThat(
                            HexFormat.of()
                                    .formatHex(
                                            MessageDigest.getInstance("SHA-256")
                                                    .digest(response.body())))
                    .isEqualTo(snapshot.checksum());
            BuildDownload expiringUrl = shortLived.download(snapshot);
            assertThat(URI.create(expiringUrl.url()).getRawQuery())
                    .contains("X-Amz-Expires=1");
            HttpRequest expiringRequest =
                    HttpRequest.newBuilder(URI.create(expiringUrl.url())).GET().build();
            Awaitility.await()
                    .atMost(Duration.ofSeconds(20))
                    .until(
                            () ->
                                    client.send(expiringRequest, HttpResponse.BodyHandlers.discarding())
                                                    .statusCode()
                                            == 403);
        } finally {
            shortLived.close();
        }
    }

    @Test
    @Tag("known-defect")
    @DisplayName("[D-041] 초 경계에서 1초 내용 URL은 첫 GET 전에 만료될 수 있다")
    void oneSecondContentUrlAtSecondBoundary() throws Exception {
        byte[] bytes = {1, 2, 3};
        ProjectSnapshot snapshot = ReviewFixtures.snapshot(1, object(bytes), bytes.length);
        HttpClient client = HttpClient.newHttpClient();
        S3ReviewBuildStorage shortLived = storageWithTtl(Duration.ofSeconds(1));
        try {
            Awaitility.await().pollInterval(Duration.ofMillis(1)).atMost(Duration.ofSeconds(3))
                    .until(() -> Instant.now().getNano() >= 950_000_000);
            BuildDownload url = shortLived.download(snapshot);
            String signedDate = URI.create(url.url()).getRawQuery().split("X-Amz-Date=")[1].split("&")[0];
            Instant expires = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    .withZone(ZoneOffset.UTC).parse(signedDate, Instant::from).plusMillis(1050);
            Awaitility.await().pollInterval(Duration.ofMillis(1)).atMost(Duration.ofSeconds(3))
                    .until(() -> Instant.now().isAfter(expires));
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(url.url())).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            // 기대: 내용 URL의 첫 GET은 200. 실제: 서명 초 단위 절삭으로 이미 만료.
            // 영향: 1초를 기다리지 않아도 초 경계에서 CI가 403으로 실패한다.
            assertThat(response.statusCode()).isEqualTo(200);
        } finally {
            shortLived.close();
        }
    }

    private S3ReviewBuildStorage storageWithTtl(Duration ttl) {
        return new StorageConfiguration().buildStorage(new ReviewStorageProperties(
                MINIO.getS3URL(), MINIO.getS3URL(), "us-east-1",
                MINIO.getUserName(), MINIO.getPassword(), "stove-builds", ttl));
    }

    @Test
    void foreignBucketsMissingFilesAndChangedSizesCannotProduceUrls() {
        byte[] bytes = {1, 2, 3};
        String path = object(bytes);
        for (ProjectSnapshot s :
                new ProjectSnapshot[] {
                    ReviewFixtures.snapshot(1, "s3://other-bucket/private.zip", 3),
                    ReviewFixtures.snapshot(
                            1,
                            "s3://stove-builds/missing-" + UUID.randomUUID() + ".zip",
                            3),
                    ReviewFixtures.snapshot(1, path, 100)
                }) {
            assertThatThrownBy(() -> storage.download(s)).isInstanceOf(BusinessException.class);
        }
    }
}
