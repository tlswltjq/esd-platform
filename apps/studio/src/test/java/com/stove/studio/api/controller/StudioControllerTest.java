package com.stove.studio.api.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.web.GlobalExceptionHandler;
import com.stove.studio.api.controller.dto.CreateProjectRequest;
import com.stove.studio.api.controller.dto.UploadBuildRequest;
import com.stove.studio.core.service.GameBuildService;
import com.stove.studio.core.service.GameProjectService;
import com.stove.studio.core.service.WorkspaceService;
import com.stove.studio.core.service.UploadSessionService;
import com.stove.studio.core.service.BuildValidationDispatcherService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.core.MethodParameter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 창작자 입점의 입구 검증.
 *
 * <p>여기서 만들어진 {@code productCode} 가 catalog · store · download 를 관통하는
 * 파티션 키가 된다(docs/event-ordering.md 2절). 비어 있는 채로 통과하면
 * 그 뒤의 순서 보장이 통째로 의미를 잃는다 — 키가 같아야 순서가 보장되는데
 * 키가 빈 문자열이면 무관한 상품들이 한 체인으로 묶인다.
 */
class StudioControllerTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final GameProjectService gameProjectService = mock(GameProjectService.class);
    private final GameBuildService gameBuildService = mock(GameBuildService.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final UploadSessionService uploadSessionService = mock(UploadSessionService.class);
    private final BuildValidationDispatcherService buildValidationService =
            mock(BuildValidationDispatcherService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new StudioController(gameProjectService, gameBuildService,
                    workspaceService, uploadSessionService, buildValidationService))
            .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                @Override
                public boolean supportsParameter(MethodParameter parameter) {
                    return parameter.getParameterType().equals(Jwt.class);
                }

                @Override
                public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                              NativeWebRequest webRequest,
                                              org.springframework.web.bind.support.WebDataBinderFactory binderFactory) {
                    return Jwt.withTokenValue("test-token")
                            .header("alg", "none")
                            .subject("test-creator")
                            .build();
                }
            })
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private String project(String productCode, String title, long price)
            throws Exception {
        return objectMapper.writeValueAsString(
                new CreateProjectRequest(productCode, title, price, "KRW"));
    }

    @Test
    @DisplayName("상품코드가 비면 400 이다 — 이 값이 하류의 파티션 키다")
    void blankProductCodeIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/studio/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(project("", "게임 A", 30_000L)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(gameProjectService, gameBuildService);
    }

    @Test
    @DisplayName("제목이 비면 400 이다")
    void blankTitleIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/studio/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(project("GAME-001", "", 30_000L)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(gameProjectService, gameBuildService);
    }

    @Test
    @DisplayName("음수 가격은 400 이다 — 무료(0원)는 허용한다")
    void negativePriceIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/studio/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(project("GAME-001", "게임 A", -1L)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(gameProjectService, gameBuildService);
    }

    @Test
    @DisplayName("빌드 파일 크기가 0 이하면 400 이다")
    void nonPositiveFileSizeIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/studio/games/1/builds")
                        .header("X-Seller-Id", 1001L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UploadBuildRequest("1.0.0", 0L, "sha256:abc"))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(gameProjectService, gameBuildService);
    }

    @Test
    @DisplayName("체크섬 없는 빌드는 400 이다 — 무결성 검증 수단이 사라진다")
    void blankChecksumIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/studio/games/1/builds")
                        .header("X-Seller-Id", 1001L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UploadBuildRequest("1.0.0", 1024L, ""))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(gameProjectService, gameBuildService);
    }

}
