package com.stove.review.api.contract;

import com.stove.common.test.OpenApiSnapshot;
import com.stove.review.ReviewIntegrationTestBase;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

class ReviewOpenApiContractTest extends ReviewIntegrationTestBase {
    @LocalServerPort int port;

    @Test
    void contextLoads() {}

    @Test
    void openApiMatchesSnapshot() {
        OpenApiSnapshot.verify(port, "review");
    }
}
