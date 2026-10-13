package com.stove.studio.api.controller;

import com.stove.common.web.response.ApiResponse;
import com.stove.studio.core.service.project.WorkspaceService;
import lombok.RequiredArgsConstructor;
import com.stove.common.security.CurrentSubject;
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
    public ApiResponse<Long> own(@CurrentSubject String subject) {
        return ApiResponse.ok(workspaceService.getOrCreatePersonal(subject).getId());
    }
}
