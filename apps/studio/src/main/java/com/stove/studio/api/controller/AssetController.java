package com.stove.studio.api.controller;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import com.stove.studio.core.domain.project.ThumbnailAsset;
import com.stove.studio.core.service.project.GetThumbnail;
import com.stove.studio.core.service.project.UploadThumbnail;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
        try (InputStream input = file.getInputStream()) {
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
