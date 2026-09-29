package com.stove.studio.core.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import com.stove.studio.core.domain.GameBuild;
import com.stove.studio.core.domain.NewUploadSession;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubmissionBuildSetTest {

    private static GameBuild build(String version, String platform, String architecture,
                                   String deltaFromVersion) {
        NewUploadSession request = new NewUploadSession(version, UUID.randomUUID().toString(),
                platform, architecture, "game.zip", 100, "a".repeat(64), null, null,
                null, null, null, UUID.randomUUID().toString(), deltaFromVersion);
        GameBuild build = GameBuild.uploading(1L, request, "s3://test/" + UUID.randomUUID());
        build.processing(100);
        build.validated("a".repeat(64));
        return build;
    }

    @Test
    void acceptsMultipleOsAndDeltaWithFullFallback() {
        assertThatCode(() -> SubmissionService.validateBuildSet(List.of(
                build("2.0", "WINDOWS", "X86_64", null),
                build("2.0", "MACOS", "ARM64", null),
                build("2.0", "WINDOWS", "X86_64", "1.0"))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsDuplicateTargetAndUnbackedDelta() {
        assertThatThrownBy(() -> SubmissionService.validateBuildSet(List.of(
                build("2.0", "WINDOWS", "X86_64", null),
                build("2.0", "WINDOWS", "X86_64", null))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> SubmissionService.validateBuildSet(List.of(
                build("2.0", "WINDOWS", "X86_64", null),
                build("2.0", "MACOS", "ARM64", "1.0"))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsMixedVersionsAndDeltaPrimary() {
        assertThatThrownBy(() -> SubmissionService.validateBuildSet(List.of(
                build("2.0", "WINDOWS", "X86_64", null),
                build("3.0", "MACOS", "ARM64", null))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> SubmissionService.validateBuildSet(List.of(
                build("2.0", "WINDOWS", "X86_64", "1.0"))))
                .isInstanceOf(BusinessException.class);
    }
}
