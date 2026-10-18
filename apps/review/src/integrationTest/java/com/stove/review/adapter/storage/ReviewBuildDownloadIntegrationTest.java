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
import java.util.HexFormat;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReviewBuildDownloadIntegrationTest extends ReviewIntegrationTestBase {
    @Autowired S3ReviewBuildStorage storage;

    @Test
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
            BuildDownload url = shortLived.download(snapshot);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.url())).GET().build();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(bytes);
            assertThat(
                            HexFormat.of()
                                    .formatHex(
                                            MessageDigest.getInstance("SHA-256")
                                                    .digest(response.body())))
                    .isEqualTo(snapshot.checksum());
            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .until(
                            () ->
                                    client.send(request, HttpResponse.BodyHandlers.discarding())
                                                    .statusCode()
                                            == 403);
        } finally {
            shortLived.close();
        }
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
