package com.stove.review;

import com.stove.common.testcontainers.InfraContainers;
import java.net.URI;
import java.util.UUID;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "stove.outbox.relay-enabled=false",
            "spring.kafka.listener.auto-startup=false"
        })
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
public abstract class ReviewIntegrationTestBase {
    public static final MinIOContainer MINIO =
            new MinIOContainer(InfraContainers.minioImage())
                    .withUserName("stove")
                    .withPassword("stove1234");
    public static final S3Client WRITER;

    static {
        MINIO.start();
        WRITER =
                S3Client.builder()
                        .endpointOverride(URI.create(MINIO.getS3URL()))
                        .region(Region.US_EAST_1)
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create("stove", "stove1234")))
                        .serviceConfiguration(
                                S3Configuration.builder().pathStyleAccessEnabled(true).build())
                        .build();
        WRITER.createBucket(CreateBucketRequest.builder().bucket("stove-builds").build());
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry p) {
        p.add("stove.review-storage.endpoint", MINIO::getS3URL);
        p.add("stove.review-storage.presign-endpoint", MINIO::getS3URL);
        p.add("stove.review-storage.access-key", MINIO::getUserName);
        p.add("stove.review-storage.secret-key", MINIO::getPassword);
    }

    public static String object(byte[] bytes) {
        String key = "projects/1/" + UUID.randomUUID() + ".zip";
        WRITER.putObject(
                PutObjectRequest.builder().bucket("stove-builds").key(key).build(),
                RequestBody.fromBytes(bytes));
        return "s3://stove-builds/" + key;
    }
}
