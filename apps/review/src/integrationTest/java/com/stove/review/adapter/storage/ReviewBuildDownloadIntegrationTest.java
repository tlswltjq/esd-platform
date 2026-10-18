package com.stove.review.adapter.storage;

import com.stove.review.config.ReviewStorageProperties;

import static org.assertj.core.api.Assertions.*;

import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.common.web.error.BusinessException;
import com.stove.review.*;
import com.stove.review.core.domain.BuildDownload;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.HexFormat;

class ReviewBuildDownloadIntegrationTest extends ReviewIntegrationTestBase {
    @Autowired S3ReviewBuildStorage storage;

    @Test
    void downloadedBytesMatchTheSubmittedBuildAndUrlExpires() throws Exception {
        byte[] bytes = "Immutable uploaded ZIP".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String checksum =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        ProjectSnapshot snapshot = ReviewFixtures.snapshot(1, object(bytes), bytes.length, checksum);
        S3ReviewBuildStorage shortLived =
                new com.stove.review.config.StorageConfiguration().buildStorage(
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
            org.awaitility.Awaitility.await()
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
                new com.stove.common.kafka.contract.payload.ProjectSnapshot[] {
                    ReviewFixtures.snapshot(1, "s3://other-bucket/private.zip", 3),
                    ReviewFixtures.snapshot(
                            1,
                            "s3://stove-builds/missing-" + java.util.UUID.randomUUID() + ".zip",
                            3),
                    ReviewFixtures.snapshot(1, path, 100)
                }) {
            assertThatThrownBy(() -> storage.download(s)).isInstanceOf(BusinessException.class);
        }
    }
}
