package com.stove.studio;

import com.stove.common.testcontainers.InfraContainers;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "stove.outbox.relay-enabled=false", "spring.kafka.listener.auto-startup=false",
            "stove.storage.encryption-enabled=false", "stove.upload.max-artifact-bytes=1048576"
        })
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
public abstract class StudioIntegrationTestBase {
    static final MinIOContainer MINIO =
            new MinIOContainer(InfraContainers.minioImage())
                    .withUserName("stove")
                    .withPassword("stove1234");

    static {
        MINIO.start();
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry properties) {
        properties.add("stove.storage.endpoint", MINIO::getS3URL);
        properties.add("stove.storage.presign-endpoint", MINIO::getS3URL);
        properties.add("stove.storage.access-key", MINIO::getUserName);
        properties.add("stove.storage.secret-key", MINIO::getPassword);
    }
}
