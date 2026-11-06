package com.stove.license.api.controller;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import com.stove.license.api.controller.dto.LicenseResponse;
import com.stove.license.api.controller.dto.StoragePage;
import com.stove.license.core.service.RegisterLicense;
import com.stove.license.core.service.SearchStorage;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 보관함: 결제한 구매분을 보관하고 고객이 라이브러리 등록을 고른다(결정 93). */
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = "oauth2", scopes = "commerce")
@RequestMapping("/api/v1/storage")
public class StorageController {
    private final SearchStorage searchStorage;
    private final RegisterLicense registerLicense;

    @GetMapping
    public ApiResponse<StoragePage> list(@CurrentSubject String buyer, @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(StoragePage.from(searchStorage.execute(buyer, page, size)));
    }

    @PostMapping("/{licenseId}/registration")
    public ApiResponse<LicenseResponse> register(@CurrentSubject String buyer, @PathVariable String licenseId) {
        return ApiResponse.ok(LicenseResponse.from(registerLicense.execute(buyer, licenseId)));
    }
}
