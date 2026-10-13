package com.stove.studio.api.controller;

import com.stove.common.web.response.ApiResponse;
import com.stove.studio.core.domain.revision.StoreAsset;
import com.stove.studio.core.service.project.WorkspaceService;
import com.stove.studio.core.service.revision.StoreAssetService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.io.IOException;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.stove.common.security.CurrentSubject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/studio")
public class StoreAssetController {

    private final WorkspaceService workspaceService;
    private final StoreAssetService assetService;

    @PostMapping(value = "/projects/{gameId}/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SecurityRequirement(name = "oauth2", scopes = "studio")
    public ApiResponse<StoreAsset> upload(@PathVariable Long gameId,
                                                        @CurrentSubject String subject,
                                                        @RequestPart("file") MultipartFile file) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        try {
            return ApiResponse.ok(assetService.upload(gameId, workspaceId, file.getBytes()));
        } catch (IOException exception) {
            throw new com.stove.common.web.error.BusinessException(
                    com.stove.common.web.error.ErrorCode.INVALID_REQUEST, "이미지를 읽을 수 없습니다.");
        }
    }

    @GetMapping("/assets/{gameId}/{assetId}")
    public ResponseEntity<Void> download(@PathVariable Long gameId, @PathVariable String assetId) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(assetService.downloadUrl(gameId, assetId))).build();
    }
}
