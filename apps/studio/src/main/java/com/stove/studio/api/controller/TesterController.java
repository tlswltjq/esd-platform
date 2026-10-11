package com.stove.studio.api.controller;

import com.stove.common.core.response.ApiResponse;
import com.stove.studio.api.controller.dto.TesterInstallationResponse;
import com.stove.studio.core.domain.release.ReleaseChannel;
import com.stove.studio.core.service.access.InternalTesterService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(TesterInstallationResponse.from(
                testerService.installation(buildId, channel, jwt.getSubject())));
    }
}
