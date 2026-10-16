package com.stove.settlement.api.application;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.common.web.response.ApiResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class CreatorWorkspaceClient {
    private static final ParameterizedTypeReference<ApiResponse<Long>> RESPONSE =
            new ParameterizedTypeReference<>() {};
    private final RestClient client;

    public CreatorWorkspaceClient(RestClient client) {
        this.client = client;
    }

    public Long ownWorkspace(String token) {
        try {
            ApiResponse<Long> response = client.get().uri("/api/v1/studio/workspace")
                    .headers(headers -> headers.setBearerAuth(token)).retrieve().body(RESPONSE);
            if (response == null || !response.success() || response.data() == null)
                throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
            return response.data();
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
        }
    }
}
