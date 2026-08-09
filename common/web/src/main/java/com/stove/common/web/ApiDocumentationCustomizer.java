package com.stove.common.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.customizers.OpenApiCustomizer;

/**
 * 생성된 API 명세에 <b>코드만 봐서는 알 수 없는 것</b>을 덧붙인다.
 *
 * <p>경로·타입·검증 제약은 springdoc 이 컨트롤러와 DTO 에서 그대로 뽑아낸다.
 * 여기서 채우는 것은 그 위의 맥락이다 — 응답 봉투 규약, 그리고 <b>신원 헤더의 성격</b>.
 *
 * <p>서비스마다 붙여 넣지 않고 한 곳에 두는 이유는 이 설명이 서비스별 사정이 아니라
 * 저장소 전체의 규약이기 때문이다. 신원 헤더는 4개 서비스에 흩어져 있는데,
 * 그중 하나만 설명이 빠지면 <b>그 하나가 "의도"가 아니라 "누락"으로 읽힌다.</b>
 */
@RequiredArgsConstructor
public class ApiDocumentationCustomizer implements OpenApiCustomizer {

    /**
     * 스켈레톤에서 신원을 대신하는 헤더들.
     *
     * <p>이 값을 클라이언트가 그대로 보낸다는 사실을 <b>명세에 드러내 둔다.</b>
     * 문서에 없으면 소스를 읽은 사람만 아는 사실이 되고, 그러면 의도한 스켈레톤이 아니라
     * 빠뜨린 인증으로 읽힌다. 드러내 두면 설명이 된다.
     */
    private static final Map<String, String> IDENTITY_HEADERS = Map.of(
            "X-Member-Id", """
                    구매자 식별자. **스켈레톤 한정 — 서버가 검증하지 않는다.**
                    실제로는 게이트웨이가 토큰을 검증한 뒤 주입할 자리이고, 그때 이 파라미터는 명세에서 사라진다.
                    지금은 값을 바꾸면 다른 회원의 자원이 조회되므로 내부망 밖에 열어서는 안 된다.""",
            "X-Seller-Id", """
                    판매자(크리에이터) 식별자. **스켈레톤 한정 — 서버가 검증하지 않는다.**
                    X-Member-Id 와 같은 자리이며, 스튜디오 API 의 소유권 판정에 그대로 쓰인다.""");

    private final String applicationName;

    @Override
    public void customise(OpenAPI openApi) {
        openApi.info(info());
        describeIdentityHeaders(openApi);
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
