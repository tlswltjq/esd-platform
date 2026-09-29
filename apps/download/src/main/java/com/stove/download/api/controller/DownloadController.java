package com.stove.download.api.controller;

import com.stove.common.core.response.ApiResponse;
import com.stove.common.security.CommerceIdentity;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import com.stove.download.api.controller.dto.DownloadTicketResponse;
import com.stove.download.api.controller.dto.ManifestResponse;
import com.stove.download.core.service.DownloadTicketService;
import com.stove.download.core.service.ManifestService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SecurityRequirement(name = "oauth2", scopes = "commerce")
@RequiredArgsConstructor
@RequestMapping("/api/v1/downloads")
public class DownloadController {

    private final DownloadTicketService downloadTicketService;
    private final ManifestService manifestService;

    /** 다운로드 인증 → CDN 서명 URL 발급 (미보유 시 403) */
    @GetMapping("/{productCode}/ticket")
    public ApiResponse<DownloadTicketResponse> ticket(@PathVariable String productCode,
                                                      @AuthenticationPrincipal Jwt jwt,
                                                      @RequestParam(required = false) String platform,
                                                      @RequestParam(required = false) String architecture,
                                                      @RequestParam(required = false) String fromVersion) {
        Long memberId = CommerceIdentity.memberId(jwt);
        var ticket = platform == null && architecture == null && fromVersion == null
                ? downloadTicketService.issue(productCode, memberId)
                : downloadTicketService.issue(productCode, memberId, platform, architecture, fromVersion);
        return ApiResponse.ok(DownloadTicketResponse.from(ticket));
    }

    /** 버전 목록(패치 이력) */
    @GetMapping("/{productCode}/manifests")
    public ApiResponse<List<ManifestResponse>> manifests(@PathVariable String productCode) {
        return ApiResponse.ok(manifestService.history(productCode).stream()
                .map(ManifestResponse::from)
                .toList());
    }
}
