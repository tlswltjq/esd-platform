package com.stove.catalog.api.controller;

import com.stove.catalog.core.domain.DownloadRestrictionChange;
import com.stove.catalog.core.domain.DownloadRestrictionView;
import com.stove.catalog.core.service.ChangeDownloadRestriction;
import com.stove.catalog.core.service.ReadDownloadRestriction;
import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/products/{code}/downloads/restriction")
public class CatalogDownloadRestrictionController {
    private final ChangeDownloadRestriction change;
    private final ReadDownloadRestriction read;
    @GetMapping
    public ResponseEntity<ApiResponse<DownloadRestrictionView>> read(@PathVariable String code) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(read.execute(code)));
    }
    @PutMapping
    public ResponseEntity<ApiResponse<DownloadRestrictionView>> change(@PathVariable String code, @CurrentSubject String actor,
            @RequestBody Request request) {
        DownloadRestrictionChange input;
        try {
            input = new DownloadRestrictionChange(code, request.restricted(), request.expectedRestrictionVersion(), request.reason());
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        DownloadRestrictionView view = change.execute(actor, input);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(view));
    }
    public record Request(Boolean restricted, long expectedRestrictionVersion, String reason) {}
}
