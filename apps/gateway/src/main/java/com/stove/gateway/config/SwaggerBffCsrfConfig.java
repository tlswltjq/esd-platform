package com.stove.gateway.config;

import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springdoc.webflux.ui.SwaggerIndexPageTransformer;
import org.springdoc.webflux.ui.SwaggerIndexTransformer;
import org.springdoc.webflux.ui.SwaggerWelcomeCommon;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 결정 220·221: Swagger UI는 OAuth 클라이언트가 아니라 BFF 세션 쿠키로 호출하는 웹 페이지다.
 * 세션 쿠키의 변경 요청은 CSRF 헤더가 필요하므로(R5.2), 같은 출처의 변경 요청마다 세션에 둔 CSRF 토큰을
 * {@code GET /bff/csrf}로 받아 그 응답이 알려 준 헤더에 붙인다. springdoc의 CSRF 주입 지점
 * ({@code springdoc.swagger-ui.csrf.enabled})을 쓰되, 쿠키 대신 {@code /bff/csrf}를 읽게 바꾼다.
 */
@Configuration
public class SwaggerBffCsrfConfig {

    static final String REQUEST_INTERCEPTOR = """
            requestInterceptor: async (request) => {
            			const method = (request.method || 'GET').toUpperCase();
            			const requestURL = new URL(request.url, document.location.origin);
            			if (requestURL.origin !== document.location.origin || ['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method)) return request;
            			const reply = await fetch('/bff/csrf', { credentials: 'same-origin' });
            			if (reply.ok) { const csrf = await reply.json(); request.headers[csrf.headerName] = csrf.token; }
            			return request;
            		},
            		presets: [""";

    @Bean
    SwaggerIndexTransformer indexPageTransformer(SwaggerUiConfigProperties swaggerUiConfig,
                                                 SwaggerUiOAuthProperties swaggerUiOAuthProperties,
                                                 SwaggerWelcomeCommon swaggerWelcomeCommon,
                                                 ObjectMapperProvider objectMapperProvider) {
        return new BffCsrfIndexTransformer(swaggerUiConfig, swaggerUiOAuthProperties, swaggerWelcomeCommon, objectMapperProvider);
    }

    static final class BffCsrfIndexTransformer extends SwaggerIndexPageTransformer {
        BffCsrfIndexTransformer(SwaggerUiConfigProperties swaggerUiConfig, SwaggerUiOAuthProperties swaggerUiOAuthProperties,
                                SwaggerWelcomeCommon swaggerWelcomeCommon, ObjectMapperProvider objectMapperProvider) {
            super(swaggerUiConfig, swaggerUiOAuthProperties, swaggerWelcomeCommon, objectMapperProvider);
        }

        @Override
        protected String addCSRF(String html) {
            return html.replace("presets: [", REQUEST_INTERCEPTOR);
        }
    }
}
