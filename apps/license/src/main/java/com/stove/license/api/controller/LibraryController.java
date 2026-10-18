package com.stove.license.api.controller;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import com.stove.license.api.controller.dto.LicensePage;
import com.stove.license.api.controller.dto.LicenseResponse;
import com.stove.license.core.service.ReadLicense;
import com.stove.license.core.service.SearchLicenses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = "oauth2", scopes = "commerce")
@RequestMapping("/api/v1/library")
public class LibraryController {
    private final SearchLicenses searchLicenses;
    private final ReadLicense readLicense;

    @GetMapping
    public ApiResponse<LicensePage> list(@CurrentSubject String buyer, @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(LicensePage.from(searchLicenses.execute(buyer, page, size)));
    }

    @GetMapping("/{productCode}")
    public ApiResponse<LicenseResponse> detail(@CurrentSubject String buyer, @PathVariable String productCode) {
        return ApiResponse.ok(LicenseResponse.from(readLicense.execute(buyer, productCode)));
    }
}
