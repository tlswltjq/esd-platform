package com.stove.common.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.customizers.OpenApiCustomizer;

/**
 * 생성된 API 명세에 <b>코드만 봐서는 알 수 없는 것</b>을 덧붙인다.
 *
 * <p>경로·타입·검증 제약은 springdoc 이 컨트롤러와 DTO 에서 그대로 뽑아낸다.
 * 여기서 채우는 것은 그 위의 맥락이다 — 응답 봉투 규약, OAuth2 로그인,
 * 그리고 아직 Studio가 사용하는 신원 헤더의 성격.
 *
 * <p>서비스마다 붙여 넣지 않고 한 곳에 두는 이유는 이 설명이 서비스별 사정이 아니라
 * 저장소 전체의 규약이기 때문이다.
 */
@RequiredArgsConstructor
public class ApiDocumentationCustomizer implements OpenApiCustomizer {

    public static final String OAUTH2 = "oauth2";
    public static final String PROJECT_CREDENTIAL = "projectCredential";

    /** Studio의 남은 스켈레톤 신원 헤더. 커머스 회원 ID는 검증된 토큰에서 읽는다. */
    private static final Map<String, String> IDENTITY_HEADERS = Map.of(
            "X-Seller-Id", """
                    판매자(크리에이터) 식별자. **스켈레톤 한정 — 서버가 검증하지 않는다.**
                    스튜디오 API 의 소유권 판정에 쓰인다.""");

    private final String applicationName;
    private final String authorizationUrl;
    private final String tokenUrl;

    @Override
    public void customise(OpenAPI openApi) {
        openApi.info(info());
        // Gateway Swagger가 하위 컨테이너 주소(studio:8085 등)를 브라우저에 노출하지 않도록
        // 현재 문서가 제공된 출처를 기준으로 API를 호출하게 한다.
        openApi.setServers(List.of(new Server().url("/")));
        describeIdentityHeaders(openApi);
        addSecuritySchemes(openApi);
    }

    private void addSecuritySchemes(OpenAPI openApi) {
        if (!List.of("studio", "review", "order", "payment", "license", "download", "settlement")
                .contains(applicationName)) {
            return;
        }
        Components components = openApi.getComponents() == null ? new Components() : openApi.getComponents();
        components.addSecuritySchemes(OAUTH2, new SecurityScheme()
                .type(SecurityScheme.Type.OAUTH2)
                .description("Swagger UI가 Authorization Code + PKCE 로그인을 수행합니다.")
                .flows(new OAuthFlows().authorizationCode(new OAuthFlow()
                        .authorizationUrl(authorizationUrl)
                        .tokenUrl(tokenUrl)
                        .scopes(new Scopes()
                                .addString("openid", "사용자 식별")
                                .addString("profile", "기본 프로필")
                                .addString("studio", "크리에이터·심사 API")
                                .addString("commerce", "구매·정산 API")))));
        if ("studio".equals(applicationName)) {
            components.addSecuritySchemes(PROJECT_CREDENTIAL, new SecurityScheme()
                    .type(SecurityScheme.Type.APIKEY)
                    .in(SecurityScheme.In.HEADER)
                    .name("X-Project-Credential")
                    .description("프로젝트 범위 CI credential입니다. 빌드 업로드와 상태 조회에만 사용합니다."));
        }
        openApi.setComponents(components);
    }

    private Info info() {
        return new Info()
                .title("STOVE %s API".formatted(applicationName))
                .version("v1")
                .description("""
                        모든 응답은 공통 봉투 `{success, data, error}` 로 감싼다.
                        실패면 `success=false` 이고 `error.code` 에 `ErrorCode` 이름이 들어간다
                        (`PRICE_MISMATCH`, `PAYMENT_AMOUNT_MISMATCH` 등). HTTP 상태는 그 코드가 정한다.

                        `data` 가 없는 성공 응답(`ApiResponse.ok()`)에서는 `data` 필드 자체가 빠진다 —
                        봉투에 `@JsonInclude(NON_NULL)` 이 걸려 있다.""");
    }

    private void describeIdentityHeaders(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        openApi.getPaths().values().stream()
                .flatMap(pathItem -> pathItem.readOperations().stream())
                .filter(operation -> operation.getParameters() != null)
                .flatMap(operation -> operation.getParameters().stream())
                .filter(parameter -> "header".equals(parameter.getIn()))
                .forEach(this::describe);
    }

    private void describe(Parameter parameter) {
        String description = IDENTITY_HEADERS.get(parameter.getName());
        if (description != null) {
            parameter.setDescription(description);
        }
    }
}
