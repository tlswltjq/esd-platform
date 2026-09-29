package com.stove.catalog.infrastructure;

import com.stove.catalog.core.port.CreatorWorkspacePort;
import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.core.response.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class CreatorWorkspaceClient implements CreatorWorkspacePort {
    private static final ParameterizedTypeReference<ApiResponse<Long>> RESPONSE =
            new ParameterizedTypeReference<>() {};
    private final RestClient client;

    public CreatorWorkspaceClient(RestClient.Builder builder,
                                  @Value("${stove.catalog.studio-url:http://localhost:8085}") String baseUrl) {
        client = builder.baseUrl(baseUrl).build();
    }

    public Long ownWorkspace(String bearerToken) {
        try {
            ApiResponse<Long> response = client.get().uri("/api/v1/studio/workspace")
                    .headers(headers -> headers.setBearerAuth(bearerToken))
                    .retrieve().body(RESPONSE);
            if (response == null || !response.success() || response.data() == null) {
                throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "창작자 워크스페이스를 확인할 수 없습니다.");
            }
            return response.data();
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "창작자 워크스페이스를 확인할 수 없습니다.");
        }
    }
}
