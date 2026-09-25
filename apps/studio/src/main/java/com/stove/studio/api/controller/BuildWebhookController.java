package com.stove.studio.api.controller;

import com.stove.common.core.response.ApiResponse;
import com.stove.studio.api.controller.dto.BuildWebhookResponse;
import com.stove.studio.api.controller.dto.RegisterBuildWebhookRequest;
import com.stove.studio.core.service.BuildWebhookService;
import com.stove.studio.core.service.WorkspaceService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/studio/projects/{gameId}/build-webhooks")
@SecurityRequirement(name = "oauth2", scopes = "studio")
public class BuildWebhookController {
    private final BuildWebhookService webhookService;
    private final WorkspaceService workspaceService;

    @PostMapping
    public ApiResponse<BuildWebhookResponse> register(
            @PathVariable Long gameId, @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RegisterBuildWebhookRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        return ApiResponse.ok(BuildWebhookResponse.from(
                webhookService.register(gameId, workspaceId, request.endpointUrl())));
    }

    @GetMapping
    public ApiResponse<List<BuildWebhookResponse>> list(
            @PathVariable Long gameId, @AuthenticationPrincipal Jwt jwt) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        return ApiResponse.ok(webhookService.list(gameId, workspaceId).stream()
                .map(BuildWebhookResponse::from).toList());
    }

    @PostMapping("/{subscriptionId}/disable")
    public ApiResponse<Void> disable(
            @PathVariable Long gameId, @PathVariable Long subscriptionId,
            @AuthenticationPrincipal Jwt jwt) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        webhookService.disable(gameId, subscriptionId, workspaceId);
        return ApiResponse.ok();
    }
}
