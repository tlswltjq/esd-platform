package com.stove.studio.api.controller;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.core.response.ApiResponse;
import com.stove.studio.api.controller.dto.CiOidcExchangeRequest;
import com.stove.studio.api.controller.dto.CiOidcExchangeResponse;
import com.stove.studio.api.controller.dto.CiScheduleReleaseRequest;
import com.stove.studio.api.controller.dto.ReleaseResponse;
import com.stove.studio.config.ProjectCredentialPrincipal;
import com.stove.studio.core.domain.ReleaseChannel;
import com.stove.studio.core.service.CiOidcExchangeService;
import com.stove.studio.core.service.ReleaseService;
import com.stove.studio.core.service.SubmissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/studio/ci")
public class CiOidcController {
    private final CiOidcExchangeService exchangeService;
    private final ReleaseService releaseService;
    private final SubmissionService submissionService;

    @PostMapping("/oidc/exchange")
    public ApiResponse<CiOidcExchangeResponse> exchange(@Valid @RequestBody CiOidcExchangeRequest request) {
        return ApiResponse.ok(CiOidcExchangeResponse.from(exchangeService.exchange(
                request.gameId(), request.provider(), request.platform(), request.oidcToken())));
    }

    @PostMapping("/projects/{gameId}/releases")
    public ApiResponse<ReleaseResponse> schedule(
            @PathVariable Long gameId, @AuthenticationPrincipal ProjectCredentialPrincipal principal,
            @Valid @RequestBody CiScheduleReleaseRequest request) {
        if (principal == null || !gameId.equals(principal.gameId()) || !principal.releaseAllowed()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "보호된 tag와 승인된 CI environment에서만 출시를 예약할 수 있습니다.");
        }
        if (!gameId.equals(submissionService.requireOwned(
                request.submissionId(), principal.workspaceId()).getGameId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "다른 프로젝트의 제출물은 예약할 수 없습니다.");
        }
        return ApiResponse.ok(ReleaseResponse.from(releaseService.create(request.submissionId(),
                principal.workspaceId(), request.publishAt(), request.timeZone(), ReleaseChannel.LIVE,
                null, "ci:" + principal.sourceProvider())));
    }
}
