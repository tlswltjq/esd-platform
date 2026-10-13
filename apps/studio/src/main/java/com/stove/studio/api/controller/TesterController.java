package com.stove.studio.api.controller;

import com.stove.common.web.response.ApiResponse;
import com.stove.studio.api.controller.dto.TesterInstallationResponse;
import com.stove.studio.core.domain.release.ReleaseChannel;
import com.stove.studio.core.service.access.InternalTesterService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import com.stove.common.security.CurrentSubject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/studio/tester")
@SecurityRequirement(name = "oauth2", scopes = "studio")
public class TesterController {
    private final InternalTesterService testerService;

    @GetMapping("/builds/{buildId}/installation")
    public ApiResponse<TesterInstallationResponse> installation(
            @PathVariable Long buildId, @RequestParam ReleaseChannel channel,
            @CurrentSubject String subject) {
        return ApiResponse.ok(TesterInstallationResponse.from(
                testerService.installation(buildId, channel, subject)));
    }
}
