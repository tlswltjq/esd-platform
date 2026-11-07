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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReviewBuildDownloadIntegrationTest extends ReviewIntegrationTestBase {
    @Autowired S3ReviewBuildStorage storage;

    @Test
    @DisplayName("[D-041] 5분 내용 URL로 제출 빌드의 바이트와 체크섬을 검증한다")
    void downloadedBytesMatchTheSubmittedBuild() throws Exception {
        byte[] bytes = "Immutable uploaded ZIP".getBytes(StandardCharsets.UTF_8);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        ProjectSnapshot snapshot = ReviewFixtures.snapshot(1, object(bytes), bytes.length, checksum);
        BuildDownload url = storage.download(snapshot);
        assertThat(queryParameter(url, "X-Amz-Expires")).isEqualTo("300");
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url.url())).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(bytes);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(response.body())))
                .isEqualTo(snapshot.checksum());
    }

    @Test
    @DisplayName("[D-041] 유효한 URL이 200으로 다운로드된 뒤 만료 오류 403으로 바뀐다")
    void validDownloadUrlExpires() throws Exception {
        byte[] bytes = {1, 2, 3};
        ProjectSnapshot snapshot = ReviewFixtures.snapshot(1, object(bytes), bytes.length);
        HttpClient client = HttpClient.newHttpClient();
        S3ReviewBuildStorage shortLived = storageWithTtl(Duration.ofSeconds(3));
        try {
            BuildDownload url = shortLived.download(snapshot);
            assertThat(queryParameter(url, "X-Amz-Expires")).isEqualTo("3");
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.url())).GET().build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(200);
            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(403);
                assertThat(response.body()).contains("Request has expired");
            });
        } finally {
            shortLived.close();
        }
    }

    @Test
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
            String signedDate = queryParameter(url, "X-Amz-Date");
            Instant expires = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    .withZone(ZoneOffset.UTC).parse(signedDate, Instant::from).plusMillis(1050);
            Awaitility.await().pollInterval(Duration.ofMillis(1)).atMost(Duration.ofSeconds(3))
                    .until(() -> Instant.now().isAfter(expires));
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(url.url())).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            // MinIO는 초 단위 서명 시각 + TTL 뒤부터 만료로 판정한다.
            // 내용 확인은 이 짧은 URL 대신 5분 URL로 분리한다.
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.body()).contains("Request has expired");
        } finally {
            shortLived.close();
        }
    }

    private String queryParameter(BuildDownload url, String name) {
        return Arrays.stream(URI.create(url.url()).getRawQuery().split("&"))
                .filter(parameter -> parameter.startsWith(name + "="))
                .map(parameter -> parameter.substring(name.length() + 1))
                .findFirst().orElseThrow();
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
