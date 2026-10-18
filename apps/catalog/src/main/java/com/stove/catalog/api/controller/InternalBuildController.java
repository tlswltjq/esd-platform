package com.stove.catalog.api.controller;

import com.stove.catalog.api.controller.dto.PublishedBuildResponse;
import com.stove.catalog.core.service.ReadPublishedBuild;
import com.stove.common.web.response.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/products")
public class InternalBuildController {
    private final ReadPublishedBuild readPublishedBuild;

    @GetMapping("/{productCode}/build")
    public ResponseEntity<ApiResponse<PublishedBuildResponse>> build(@PathVariable String productCode) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                ApiResponse.ok(PublishedBuildResponse.from(readPublishedBuild.execute(productCode))));
    }
}
