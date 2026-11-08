package com.stove.common.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 결정 118: 직접 만드는 MinIO 이미지는 Docker Hub의 다른 사람 계정(stove)에서 받지 않는다. */
class InfraContainersTest {

    private static final String TAG = "RELEASE.2025-04-22T22-12-26Z-local";

    @Test
    @DisplayName("변수가 없으면 Docker Hub로 해석되지 않는 localhost/stove 이름을 쓴다")
    void defaultImageIsLocalRegistry() {
        assertThat(InfraContainers.minioImage(Map.of()).asCanonicalNameString())
                .isEqualTo("localhost/stove/minio:" + TAG);
    }

    @Test
    @DisplayName("테스트 이름이 공급 이미지·registry보다 먼저다")
    void testImageWins() {
        Map<String, String> environment = Map.of(
                "STOVE_TEST_MINIO_IMAGE", " ghcr.io/team/minio:test ",
                "STOVE_MINIO_IMAGE", "vendor/minio:test",
                "STOVE_JIB_REGISTRY", "example.invalid/team");

        assertThat(InfraContainers.minioImage(environment).asCanonicalNameString())
                .isEqualTo("ghcr.io/team/minio:test");
    }

    @Test
    @DisplayName("registry를 바꾸면 기본 이름도 그 registry를 따른다")
    void registryChangesDefault() {
        assertThat(InfraContainers.minioImage(Map.of("STOVE_JIB_REGISTRY", "example.invalid/team"))
                .asCanonicalNameString())
                .isEqualTo("example.invalid/team/minio:" + TAG);
    }

    @ParameterizedTest
    @CsvSource({
            "STOVE_TEST_MINIO_IMAGE, stove/minio:" + TAG,
            "STOVE_MINIO_IMAGE, docker.io/stove/minio:" + TAG,
            "STOVE_TEST_MINIO_IMAGE, index.docker.io/stove/minio",
            "STOVE_TEST_MINIO_IMAGE, Docker.IO/stove/minio:t",
            "STOVE_TEST_MINIO_IMAGE, stove/minio:t@sha256:0000000000000000000000000000000000000000000000000000000000000000",
            "STOVE_JIB_REGISTRY, stove",
            "STOVE_JIB_REGISTRY, docker.io/stove"
    })
    @DisplayName("Docker Hub의 stove 이름은 받지 않고 설정 이름과 함께 거절한다")
    void dockerHubStoveIsRefused(String setting, String value) {
        assertThatThrownBy(() -> InfraContainers.minioImage(Map.of(setting, value)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(setting)
                .hasMessageContaining("localhost/stove");
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost/stove/minio:t", "localhost:5000/stove/minio:t", "ghcr.io/stove/minio:t",
            "127.0.0.1:5000/stove/minio:t", "stove-team/minio:t", "minio/minio:t"})
    @DisplayName("다른 registry나 다른 계정의 이름은 그대로 쓴다")
    void otherNamesAreAllowed(String image) {
        assertThat(InfraContainers.minioImage(Map.of("STOVE_TEST_MINIO_IMAGE", image)).asCanonicalNameString())
                .isEqualTo(image);
    }
}
