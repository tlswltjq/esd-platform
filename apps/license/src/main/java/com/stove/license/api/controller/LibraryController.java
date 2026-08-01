package com.stove.license.api;

import com.stove.common.core.response.ApiResponse;
import com.stove.license.api.dto.LicenseResponse;
import com.stove.license.application.LicenseService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/library")
public class LibraryController {

    private final LicenseService licenseService;

    /** 내 보유 라이브러리 (download 가 다운로드 인증에 사용) */
    @GetMapping
    public ApiResponse<List<LicenseResponse>> myLibrary(@RequestHeader("X-Member-Id") Long memberId) {
        return ApiResponse.ok(licenseService.getLibrary(memberId).stream()
                .map(LicenseResponse::from)
                .toList());
    }
}
