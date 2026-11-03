package com.stove.catalog.api.controller;

import com.stove.catalog.api.controller.dto.PublicationJobResponse;
import com.stove.catalog.core.service.ReadPublicationJobs;
import com.stove.common.web.response.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/catalog-release-jobs")
public class PublicationJobController {
    private final ReadPublicationJobs read;

    @GetMapping
    public ResponseEntity<ApiResponse<List<PublicationJobResponse>>> list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(
                read.list(page, size).stream().map(PublicationJobResponse::from).toList()));
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<ApiResponse<PublicationJobResponse>> get(@PathVariable Long requestId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(PublicationJobResponse.from(read.get(requestId))));
    }
}
