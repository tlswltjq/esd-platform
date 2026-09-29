package com.stove.studio.api.controller;

import com.stove.common.core.response.ApiResponse;
import com.stove.studio.core.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/studio/workspace")
public class WorkspaceController {
    private final WorkspaceService workspaceService;

    /** Resolve the seller from the authenticated subject, never a caller supplied seller ID. */
    @GetMapping
    public ApiResponse<Long> own(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(workspaceService.getOrCreatePersonal(jwt.getSubject()).getId());
    }
}
