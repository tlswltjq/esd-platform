package com.stove.download.api.controller;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.common.web.response.ApiResponse;
import com.stove.download.api.controller.dto.DownloadTicketResponse;
import com.stove.download.core.domain.DownloadRequest;
import com.stove.download.core.service.ReadBuildDownload;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/downloads")
@SecurityRequirement(name = "oauth2", scopes = "commerce")
public class DownloadController {
    private final ReadBuildDownload readBuildDownload;

    @GetMapping("/{productCode}/ticket")
    public ResponseEntity<ApiResponse<DownloadTicketResponse>> ticket(@PathVariable String productCode,
            @CurrentSubject String buyer, @AuthenticationPrincipal Jwt jwt,
            @Parameter(hidden = true) @RequestParam Map<String, String> parameters) {
        if (!parameters.isEmpty()) throw new BusinessException(ErrorCode.INVALID_REQUEST, "쿼리 입력을 지원하지 않습니다.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(
                DownloadTicketResponse.from(readBuildDownload.execute(
                        new DownloadRequest(buyer, productCode), jwt.getTokenValue()))));
    }
}
