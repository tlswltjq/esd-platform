package com.stove.license.api.controller;

import com.stove.common.core.response.ApiResponse;
import com.stove.common.security.CommerceIdentity;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import com.stove.license.api.controller.dto.LicenseResponse;
import com.stove.license.core.service.LicenseService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SecurityRequirement(name = "oauth2", scopes = "commerce")
@RequiredArgsConstructor
@RequestMapping("/api/v1/library")
public class LibraryController {

    private final LicenseService licenseService;

    /** 내 보유 라이브러리 (download 가 다운로드 인증에 사용) */
    @GetMapping
    public ApiResponse<List<LicenseResponse>> myLibrary(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(licenseService.getLibrary(CommerceIdentity.memberId(jwt)).stream()
                .map(LicenseResponse::from)
                .toList());
    }
}
