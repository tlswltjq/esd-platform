package com.stove.studio.api.controller;

import com.stove.common.core.response.ApiResponse;
import com.stove.studio.core.service.StoreAssetService;
import com.stove.studio.core.domain.StoreAsset;
import com.stove.studio.core.service.WorkspaceService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.net.URI;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
                                                        @AuthenticationPrincipal Jwt jwt,
                                                        @RequestPart("file") MultipartFile file) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        try {
            return ApiResponse.ok(assetService.upload(gameId, workspaceId, file.getBytes()));
        } catch (IOException exception) {
            throw new com.stove.common.core.error.BusinessException(
                    com.stove.common.core.error.ErrorCode.INVALID_REQUEST, "이미지를 읽을 수 없습니다.");
        }
    }

    @GetMapping("/assets/{gameId}/{assetId}")
    public ResponseEntity<Void> download(@PathVariable Long gameId, @PathVariable String assetId) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(assetService.downloadUrl(gameId, assetId))).build();
    }
}
