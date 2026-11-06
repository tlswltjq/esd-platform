package com.stove.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

/** 기본 설정에서는 ADMIN에게도 공통 운영 API가 존재하지 않는다. */
class RecoveryOpsDefaultIntegrationTest extends StudioIntegrationTestBase {
    @LocalServerPort int port;
    @MockitoBean JwtDecoder decoder;

    @Test
    void opsApiIsAbsentUntilAnInstanceExplicitlyEnablesIt() {
        when(decoder.decode(anyString())).thenReturn(Jwt.withTokenValue("ADMIN").header("alg", "RS256")
                .subject("operator").claim("roles", List.of("ADMIN")).build());
        for (String path : List.of("/api/v1/ops/outbox/dead", "/api/v1/ops/dlt?topic=stove.studio.v1.DLT")) {
            int status = RestClient.create("http://localhost:" + port).method(HttpMethod.GET).uri(path)
                    .header("Authorization", "Bearer ADMIN")
                    .exchange((request, response) -> response.getStatusCode().value());
            assertThat(status).isEqualTo(404);
        }
    }
}
