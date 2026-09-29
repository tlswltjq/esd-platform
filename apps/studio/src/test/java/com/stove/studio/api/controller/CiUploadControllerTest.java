package com.stove.studio.api.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.stove.common.core.error.BusinessException;
import com.stove.studio.api.controller.dto.CreateUploadSessionRequest;
import com.stove.studio.config.ProjectCredentialPrincipal;
import com.stove.studio.core.service.BuildValidationDispatcherService;
import com.stove.studio.core.service.UploadSessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CiUploadControllerTest {
    private final UploadSessionService uploadSessionService = mock(UploadSessionService.class);
    private final BuildValidationDispatcherService validationService =
            mock(BuildValidationDispatcherService.class);
    private final CiUploadController controller =
            new CiUploadController(uploadSessionService, validationService);

    @Test
    @DisplayName("OIDC credential이 확정한 repository·ref·platform과 다른 업로드를 거절한다")
    void enforcesOidcTrustConstraints() {
        ProjectCredentialPrincipal principal = new ProjectCredentialPrincipal(
                10L, 20L, 30L, "acme/game", "refs/tags/v1.0.0", "WINDOWS",
                true, "github", "production");

        assertRejected(principal, request("other/game", "refs/tags/v1.0.0", "WINDOWS"));
        assertRejected(principal, request("acme/game", "refs/heads/main", "WINDOWS"));
        assertRejected(principal, request("acme/game", "refs/tags/v1.0.0", "LINUX"));
        verifyNoInteractions(uploadSessionService, validationService);
    }

    private void assertRejected(ProjectCredentialPrincipal principal,
                                CreateUploadSessionRequest request) {
        assertThatThrownBy(() -> controller.create(20L, principal, request))
                .isInstanceOf(BusinessException.class);
    }

    private CreateUploadSessionRequest request(String repository, String sourceRef, String platform) {
        return new CreateUploadSessionRequest("1.0.0", "100", platform, "X86_64",
                "game.zip", 1_024L, "a".repeat(64), "deadbeef", repository, sourceRef,
                "GITHUB", "100", "idempotency-100");
    }
}
