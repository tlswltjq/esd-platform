package com.stove.review.infrastructure.storage;

import com.stove.review.config.ReviewStorageProperties;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ReviewStoragePropertiesTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReviewStorageProperties.class)
    static class PropertiesConfig {}

    @Test
    void genericWriterCredentialsCannotSubstituteForTheReviewAccount() {
        var context =
                new ApplicationContextRunner()
                        .withUserConfiguration(PropertiesConfig.class)
                        .withPropertyValues(
                                "stove.storage.access-key=writer",
                                "stove.storage.secret-key=writer-secret");
        context.run(result -> assertThat(result).hasFailed());
        context.withPropertyValues(
                        "stove.review-storage.access-key=reader",
                        "stove.review-storage.secret-key=reader-secret")
                .run(
                        result ->
                                assertThat(result)
                                        .hasNotFailed()
                                        .hasSingleBean(ReviewStorageProperties.class));
    }
}
