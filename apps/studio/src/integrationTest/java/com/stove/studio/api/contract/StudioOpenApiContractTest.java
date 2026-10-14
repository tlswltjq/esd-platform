package com.stove.studio.api.contract;

import com.stove.common.test.OpenApiSnapshot;
import com.stove.studio.StudioIntegrationTestBase;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

class StudioOpenApiContractTest extends StudioIntegrationTestBase {
    @LocalServerPort private int port;

    @Test
    void contextLoads() {}

    @Test
    void openApiMatchesSnapshot() {
        OpenApiSnapshot.verify(port, "studio");
    }
}
