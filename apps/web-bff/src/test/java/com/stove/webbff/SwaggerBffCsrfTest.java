package com.stove.webbff;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

/** 결정 220·221: Swagger는 OAuth 로그인을 하지 않고, 변경 요청마다 세션 CSRF 토큰을 {@code /bff/csrf}에서 받아 붙인다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SwaggerBffCsrfTest {

    @Autowired WebTestClient http;

    @Test
    void theSwaggerInitializerAddsTheSessionCsrfHeaderAndDoesNotStartOauth() {
        String location = http.get().uri("/swagger-ui.html").exchange().expectStatus().is3xxRedirection()
                .returnResult(String.class).getResponseHeaders().getLocation().getPath();
        assertThat(location).isEqualTo("/swagger-ui/index.html");
        String initializer = http.get().uri("/swagger-ui/swagger-initializer.js").exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

        assertThat(initializer).contains("requestInterceptor: async (request) =>", "fetch('/bff/csrf'",
                "request.headers[csrf.headerName] = csrf.token");
        assertThat(initializer).doesNotContain("initOAuth", "document.cookie");
    }
}
