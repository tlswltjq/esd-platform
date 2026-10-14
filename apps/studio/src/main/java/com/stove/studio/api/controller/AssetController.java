package com.stove.studio.api.controller;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import com.stove.studio.core.domain.project.ThumbnailAsset;
import com.stove.studio.core.service.project.*;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/studio")
public class AssetController {
    private final UploadThumbnail uploadThumbnail;
    private final GetThumbnail getThumbnail;

    @PostMapping(value = "/projects/{id}/thumbnail", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ThumbnailAsset> upload(
            @PathVariable Long id,
            @CurrentSubject String subject,
            @RequestPart("file") MultipartFile file)
            throws IOException {
        try (var input = file.getInputStream()) {
            return ApiResponse.ok(uploadThumbnail.execute(id, subject, input));
        }
    }

    @GetMapping("/assets/{id}/{assetId}")
    public ResponseEntity<Void> get(@PathVariable Long id, @PathVariable String assetId) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(getThumbnail.execute(id, assetId)))
                .build();
    }
}
