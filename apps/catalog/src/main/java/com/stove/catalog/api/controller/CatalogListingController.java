package com.stove.catalog.api.controller;

import com.stove.catalog.core.domain.CatalogListingView;
import com.stove.catalog.core.domain.ListingChange;
import com.stove.catalog.core.domain.ListingPreference;
import com.stove.catalog.core.domain.PlatformListingChange;
import com.stove.catalog.core.service.ChangeCatalogListing;
import com.stove.catalog.core.service.ReadCatalogListing;
import com.stove.common.security.CurrentSubject;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.common.web.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CatalogListingController {
    private final ChangeCatalogListing change;
    private final ReadCatalogListing read;
    @GetMapping("/api/v1/products/by-code/{code}/listing")
    public ResponseEntity<ApiResponse<CatalogListingView>> seller(@PathVariable String code, @CurrentSubject String actor) {
        return response(read.seller(actor, code));
    }
    @PutMapping("/api/v1/products/by-code/{code}/listing")
    public ResponseEntity<ApiResponse<CatalogListingView>> seller(@PathVariable String code, @CurrentSubject String actor, @RequestBody SellerRequest request) {
        ListingChange input;
        try { input = new ListingChange(code, request.preference(), request.expectedCatalogVersion()); }
        catch (IllegalArgumentException invalid) { throw new BusinessException(ErrorCode.INVALID_REQUEST); }
        return response(change.seller(actor, input));
    }
    @GetMapping("/api/v1/admin/products/{code}/listing/restriction")
    public ResponseEntity<ApiResponse<CatalogListingView>> platform(@PathVariable String code) { return response(read.platform(code)); }
    @PutMapping("/api/v1/admin/products/{code}/listing/restriction")
    public ResponseEntity<ApiResponse<CatalogListingView>> platform(@PathVariable String code, @CurrentSubject String actor, @RequestBody PlatformRequest request) {
        PlatformListingChange input;
        try { input = new PlatformListingChange(code, request.hidden(), request.expectedCatalogVersion()); }
        catch (IllegalArgumentException invalid) { throw new BusinessException(ErrorCode.INVALID_REQUEST); }
        return response(change.platform(actor, input));
    }
    private ResponseEntity<ApiResponse<CatalogListingView>> response(CatalogListingView view) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(view));
    }
    public record SellerRequest(ListingPreference preference, long expectedCatalogVersion) {}
    public record PlatformRequest(Boolean hidden, long expectedCatalogVersion) {}
}
